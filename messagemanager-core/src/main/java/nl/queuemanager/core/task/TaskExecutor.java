package nl.queuemanager.core.task;


public interface TaskExecutor {

	/**
	 * Clear the queue of this TaskExecutor. Any running tasks will be signalled to
	 * stop and any queued or waiting tasks will be removed from the queue.
	 */
	public abstract void clearQueue();

	/**
	 * Execute the given task at some point in the future. Possibly on a background thread.
	 * 
	 * @param task
	 */
	public abstract void execute(final Task task);

	/**
	 * Execute the given Tasks in the order they are given. Each Task
	 * will be given a dependency to the previous Task in the array. The Tasks 
	 * are guaranteed to run in-order, but not guaranteed to run on the same Thread.
	 * 
	 * @param tasks
	 */
	public abstract void executeInOrder(Task... tasks);
	
	/**
	 * Set the default thread context ClassLoader to use when executing tasks.
	 * @param contextClassLoader
	 */
	public void setContextClassLoader(ClassLoader contextClassLoader);


	/**
	 * Get the current context classloader
	 * @return
	 */
	public ClassLoader getContextClassLoader();

}