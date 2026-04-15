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

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;

import uk.ac.babraham.FastQC.FastQCConfig;
import uk.ac.babraham.FastQC.Modules.QCModule;
import uk.ac.babraham.FastQC.Sequence.SequenceFile;

public class AnalysisQueue implements Runnable, AnalysisListener{

	private static AnalysisQueue instance = new AnalysisQueue();

	private LinkedBlockingDeque<AnalysisRunner>queue = new LinkedBlockingDeque<AnalysisRunner>();

	private Semaphore slots;

	public static AnalysisQueue getInstance () {
		return instance;
	}

	private AnalysisQueue () {
		int threadCount = 1;
		if (FastQCConfig.getInstance().threads != null) {
			threadCount = FastQCConfig.getInstance().threads;
		}
		slots = new Semaphore(threadCount);

		Thread t = new Thread(this);
		t.setDaemon(true);
		t.start();
	}

	public void addToQueue (AnalysisRunner runner) {
		queue.add(runner);
	}

	public void run() {
		while (true) {
			try {
				AnalysisRunner currentRun = queue.take();
				slots.acquire();
				currentRun.addAnalysisListener(this);
				Thread t = new Thread(currentRun);
				t.start();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
	}

	public void analysisComplete(SequenceFile file, QCModule[] results) {
		slots.release();
	}

	public void analysisUpdated(SequenceFile file, int sequencesProcessed, int percentComplete) {}

	public void analysisExceptionReceived(SequenceFile file, Exception e) {
		slots.release();
	}

	public void analysisStarted(SequenceFile file) {}


}
