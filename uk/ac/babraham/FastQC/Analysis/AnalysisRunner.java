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

	// Batch size for queue transfer — larger batches reduce lock contention
	private static final int BATCH_SIZE = 1024;
	// Number of batches buffered between reader and processor
	private static final int QUEUE_CAPACITY = 32;

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

		// Parallel pipeline: reader thread decompresses + parses into batches,
		// this thread processes batches through modules. Batching reduces
		// queue lock contention vs per-sequence queueing.
		ArrayBlockingQueue<Sequence[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
		final Sequence[] POISON = new Sequence[0];
		final SequenceFormatException[] readerError = { null };

		Thread reader = new Thread(() -> {
			try {
				Sequence[] batch = new Sequence[BATCH_SIZE];
				int idx = 0;
				while (file.hasNext()) {
					batch[idx++] = file.next();
					if (idx == BATCH_SIZE) {
						queue.put(batch);
						batch = new Sequence[BATCH_SIZE];
						idx = 0;
					}
				}
				// Flush remaining sequences as a partial batch
				if (idx > 0) {
					Sequence[] partial = new Sequence[idx];
					System.arraycopy(batch, 0, partial, 0, idx);
					queue.put(partial);
				}
			} catch (SequenceFormatException e) {
				readerError[0] = e;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			try { queue.put(POISON); } catch (InterruptedException e) {}
		}, "fastqc-reader");
		reader.setDaemon(true);
		reader.start();

		int seqCount = 0;
		try {
			while (true) {
				Sequence[] batch = queue.take();
				if (batch == POISON) break;

				if (readerError[0] != null) {
					for (int li = 0; li < listeners.size(); li++) {
						listeners.get(li).analysisExceptionReceived(file, readerError[0]);
					}
					return;
				}

				for (int b = 0; b < batch.length; b++) {
					Sequence seq = batch[b];
					++seqCount;

					for (int m = 0; m < modules.length; m++) {
						if (seq.isFiltered() && modules[m].ignoreFilteredSequences()) continue;
						modules[m].processSequence(seq);
					}
				}

				if (seqCount % 1000 < BATCH_SIZE) {
					if (file.getPercentComplete() >= percentComplete + 5) {
						percentComplete = (((int) file.getPercentComplete()) / 5) * 5;
						for (int li = 0; li < listeners.size(); li++) {
							listeners.get(li).analysisUpdated(file, seqCount, percentComplete);
						}
					}
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		try { reader.join(); } catch (InterruptedException e) {}

		// Check for reader error after stream end
		if (readerError[0] != null) {
			for (int li = 0; li < listeners.size(); li++) {
				listeners.get(li).analysisExceptionReceived(file, readerError[0]);
			}
			return;
		}

		// We need to account for their potentially being no sequences
		// in the file.  In this case the BasicStats module never gets
		// the file name so we need to explicitly pass it.

		if (seqCount == 0) {
			for (int m=0; m<modules.length; m++) {
				if (modules[m] instanceof BasicStats) {
					((BasicStats)modules[m]).setFileName(file.name());
				}
			}
		}

		for (int li = 0; li < listeners.size(); li++) {
			listeners.get(li).analysisComplete(file, modules);
		}

	}

}
