/**
 * Copyright Copyright 2010-17 Simon Andrews
 *
 *    This file is part of FastQC.
 *
 *    FastQC is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    FastQC is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with FastQC; if not, write to the Free Software
 *    Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package uk.ac.babraham.FastQC.Analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;

import uk.ac.babraham.FastQC.Modules.BasicStats;
import uk.ac.babraham.FastQC.Modules.QCModule;
import uk.ac.babraham.FastQC.Sequence.Sequence;
import uk.ac.babraham.FastQC.Sequence.SequenceFile;
import uk.ac.babraham.FastQC.Sequence.SequenceFormatException;

public class AnalysisRunner implements Runnable {

	private SequenceFile file;
	private QCModule [] modules;
	private List<AnalysisListener> listeners = new ArrayList<AnalysisListener>();
	private int percentComplete = 0;

	private static final int BATCH_SIZE = 1024;
	private static final int QUEUE_CAPACITY = 32;
	// Number of parallel module processing threads
	private static final int NUM_PROCESSORS = 2;

	public AnalysisRunner (SequenceFile file) {
		this.file = file;
	}

	public void addAnalysisListener (AnalysisListener l) {
		if (l != null && !listeners.contains(l)) {
			listeners.add(l);
		}
	}

	public void removeAnalysisListener (AnalysisListener l) {
		if (l != null && listeners.contains(l)) {
			listeners.remove(l);
		}
	}


	public void startAnalysis (QCModule [] modules) {
		this.modules = modules;
		for (int i=0;i<modules.length;i++) {
			modules[i].reset();
		}
		AnalysisQueue.getInstance().addToQueue(this);
	}

	public void run() {

		for (int li = 0; li < listeners.size(); li++) {
			listeners.get(li).analysisStarted(file);
		}

		// Three-stage parallel pipeline:
		// 1. Reader thread: decompress + parse FASTQ into batched queue
		// 2. N processor threads: each handles a subset of modules
		// This uses 1+N CPU cores for overlapped I/O and computation.

		// Create N queues, one per processor thread. The dispatcher
		// copies each batch reference to all processor queues.
		@SuppressWarnings("unchecked")
		ArrayBlockingQueue<Sequence[]>[] procQueues = new ArrayBlockingQueue[NUM_PROCESSORS];
		for (int i = 0; i < NUM_PROCESSORS; i++) {
			procQueues[i] = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
		}
		final Sequence[] POISON = new Sequence[0];
		final SequenceFormatException[] readerError = { null };

		// Split modules across processors
		QCModule[][] moduleSplits = new QCModule[NUM_PROCESSORS][];
		int splitSize = (modules.length + NUM_PROCESSORS - 1) / NUM_PROCESSORS;
		for (int p = 0; p < NUM_PROCESSORS; p++) {
			int from = p * splitSize;
			int to = Math.min(from + splitSize, modules.length);
			moduleSplits[p] = new QCModule[to - from];
			System.arraycopy(modules, from, moduleSplits[p], 0, to - from);
		}

		// Reader thread
		Thread reader = new Thread(() -> {
			try {
				Sequence[] batch = new Sequence[BATCH_SIZE];
				int idx = 0;
				while (file.hasNext()) {
					batch[idx++] = file.next();
					if (idx == BATCH_SIZE) {
						for (int p = 0; p < NUM_PROCESSORS; p++) {
							procQueues[p].put(batch);
						}
						batch = new Sequence[BATCH_SIZE];
						idx = 0;
					}
				}
				if (idx > 0) {
					Sequence[] partial = new Sequence[idx];
					System.arraycopy(batch, 0, partial, 0, idx);
					for (int p = 0; p < NUM_PROCESSORS; p++) {
						procQueues[p].put(partial);
					}
				}
			} catch (SequenceFormatException e) {
				readerError[0] = e;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			for (int p = 0; p < NUM_PROCESSORS; p++) {
				try { procQueues[p].put(POISON); } catch (InterruptedException e) {}
			}
		}, "fastqc-reader");
		reader.setDaemon(true);
		reader.start();

		// Processor threads
		CountDownLatch processorsDone = new CountDownLatch(NUM_PROCESSORS);
		int[] seqCounts = new int[NUM_PROCESSORS];

		for (int p = 0; p < NUM_PROCESSORS; p++) {
			final int procId = p;
			final QCModule[] myModules = moduleSplits[p];
			final ArrayBlockingQueue<Sequence[]> myQueue = procQueues[p];

			Thread processor = new Thread(() -> {
				try {
					int count = 0;
					while (true) {
						Sequence[] batch = myQueue.take();
						if (batch == POISON) break;

						for (int b = 0; b < batch.length; b++) {
							Sequence seq = batch[b];
							count++;
							for (int m = 0; m < myModules.length; m++) {
								if (seq.isFiltered() && myModules[m].ignoreFilteredSequences()) continue;
								myModules[m].processSequence(seq);
							}
						}
					}
					seqCounts[procId] = count;
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					processorsDone.countDown();
				}
			}, "fastqc-proc-" + p);
			processor.setDaemon(true);
			processor.start();
		}

		// Wait for all processors
		try {
			processorsDone.await();
			reader.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		int seqCount = seqCounts[0]; // All processors see the same sequences

		// Check for reader error
		if (readerError[0] != null) {
			for (int li = 0; li < listeners.size(); li++) {
				listeners.get(li).analysisExceptionReceived(file, readerError[0]);
			}
			return;
		}

		if (seqCount == 0) {
			for (int m = 0; m < modules.length; m++) {
				if (modules[m] instanceof BasicStats) {
					((BasicStats) modules[m]).setFileName(file.name());
				}
			}
		}

		for (int li = 0; li < listeners.size(); li++) {
			listeners.get(li).analysisComplete(file, modules);
		}

	}

}
