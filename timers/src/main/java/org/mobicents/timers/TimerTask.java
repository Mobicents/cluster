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
