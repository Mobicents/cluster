/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.mobicents.timers;

import java.io.Serializable;

import org.apache.log4j.Logger;


/**
 * Runnable to cancel a timer task after the tx commits.
 * @author martins
 *
 */
public class CancelTimerAfterTxCommitRunnable implements Runnable {

	private static final Logger logger = Logger.getLogger(CancelTimerAfterTxCommitRunnable.class);
	
	private final FaultTolerantScheduler executor;	
	private TimerTask task;

	CancelTimerAfterTxCommitRunnable(TimerTask task,FaultTolerantScheduler executor) {
		this.task = task;
		this.executor = executor;
	}

	/*
	 * (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	public void run() {
		
		final TimerTaskData taskData = task.getData();
		final Serializable taskID = taskData.getTaskID();
		
		if (logger.isDebugEnabled()) {
			logger.debug("Cancelling timer task for timer ID "+taskID);
		}
		
		executor.getLocalRunningTasksMap().remove(taskID);
		
		try {
			task.cancel();					
		}
		catch (Throwable e) {
			logger.error(e.getMessage(),e);
		}
	}
	
}
