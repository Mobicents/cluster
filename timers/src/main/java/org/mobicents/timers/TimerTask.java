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

import java.util.concurrent.ScheduledFuture;

/**
 * The base class to implement a task to be scheduled and executed by an {@link FaultTolerantScheduler}.
 * 
 * @author martins
 *
 */
public abstract class TimerTask implements Runnable {
	
	/**
	 * the data associated with the task
	 */
	private final TimerTaskData data;
	
	/**
	 * the schedule future object that returns from the task scheduling
	 */
	private ScheduledFuture<?> scheduledFuture;
	
	/**
	 * the tx action to set the timer when the tx commits, not used in a non tx environment 
	 */
	private SetTimerAfterTxCommitRunnable action;
	
	/**
	 * 
	 * @param data
	 */
	public TimerTask(TimerTaskData data) {
		this.data = data;
	}
	
	/**
	 * Retrieves the data associated with the task.
	 * @return
	 */
	public TimerTaskData getData() {
		return data;
	}
	
	/**
	 * Retrieves the tx action to set the timer when the tx commits, not used in a non tx environment.
	 * @return
	 */
	protected SetTimerAfterTxCommitRunnable getSetTimerTransactionalAction() {
		return action;
	}

	/**
	 * Sets the tx action to set the timer when the tx commits, not used in a non tx environment.
	 * @param action
	 */
	void setSetTimerTransactionalAction(
			SetTimerAfterTxCommitRunnable action) {
		this.action = action;
	}

	/**
	 * Retrieves the schedule future object that returns from the task scheduling.
	 * @return
	 */
	public ScheduledFuture<?> getScheduledFuture() {
		return scheduledFuture;
	}
	
	/**
	 * Sets the schedule future object that returns from the task scheduling.
	 * @param scheduledFuture
	 */
	protected void setScheduledFuture(ScheduledFuture<?> scheduledFuture) {
		this.scheduledFuture = scheduledFuture;
		// it may happen that the cancel() is invoked before this is 
		// invoked 
		if (cancel) {
			this.scheduledFuture.cancel(false);
		}
	}

	private transient boolean cancel; 
	
	/**
	 * Cancels the execution of the task.
	 * Note, this doesn't remove the task from the scheduler.
	 */
	protected void cancel() {
		cancel = true;
		if (scheduledFuture != null) {
			scheduledFuture.cancel(false);
		}
	}
	
	/**
	 * The method executed by the scheduler
	 */
	public abstract void run();
	
	/**
	 * Invoked before a task is recovered, after fail over, by default simply adjust start time.
	 */
	public void beforeRecover() {
		final long now = System.currentTimeMillis();
		if (data.getStartTime() < now) {
			data.setStartTime(now);
		}
	}
	
}
