/*
 * Copyright 2002-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.context.event;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.core.ResolvableType;
import org.springframework.util.ErrorHandler;

/**
 * Simple implementation of the {@link ApplicationEventMulticaster} interface.
 *
 * <p>Multicasts all events to all registered listeners, leaving it up to
 * the listeners to ignore events that they are not interested in.
 * Listeners will usually perform corresponding {@code instanceof}
 * checks on the passed-in event object.
 *
 * <p>By default, all listeners are invoked in the calling thread.
 * This allows the danger of a rogue listener blocking the entire application,
 * but adds minimal overhead. Specify an alternative task executor to have
 * listeners executed in different threads, for example from a thread pool.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @author Brian Clozel
 * @see #setTaskExecutor
 */
public class SimpleApplicationEventMulticaster extends AbstractApplicationEventMulticaster {

	private @Nullable Executor taskExecutor;

	private @Nullable ErrorHandler errorHandler;

	private volatile @Nullable Log lazyLogger;


	/**
	 * Create a new SimpleApplicationEventMulticaster.
	 */
	public SimpleApplicationEventMulticaster() {
	}

	/**
	 * Create a new SimpleApplicationEventMulticaster for the given BeanFactory.
	 */
	public SimpleApplicationEventMulticaster(BeanFactory beanFactory) {
		setBeanFactory(beanFactory);
	}


	/**
	 * Set a custom executor (typically a {@link org.springframework.core.task.TaskExecutor})
	 * to invoke each listener with.
	 * <p>Default is equivalent to {@link org.springframework.core.task.SyncTaskExecutor},
	 * executing all listeners synchronously in the calling thread.
	 * <p>Consider specifying an asynchronous task executor here to not block the caller
	 * until all listeners have been executed. However, note that asynchronous execution
	 * will not participate in the caller's thread context (class loader, transaction context)
	 * unless the TaskExecutor explicitly supports this.
	 * <p>{@link ApplicationListener} instances which declare no support for asynchronous
	 * execution ({@link ApplicationListener#supportsAsyncExecution()} always run within
	 * the original thread which published the event, for example, the transaction-synchronized
	 * {@link org.springframework.transaction.event.TransactionalApplicationListener}.
	 * @since 2.0
	 * @see org.springframework.core.task.SyncTaskExecutor
	 * @see org.springframework.core.task.SimpleAsyncTaskExecutor
	 */
	public void setTaskExecutor(@Nullable Executor taskExecutor) {
		this.taskExecutor = taskExecutor;
	}

	/**
	 * Return the current task executor for this multicaster.
	 * @since 2.0
	 */
	protected @Nullable Executor getTaskExecutor() {
		return this.taskExecutor;
	}

	/**
	 * Set the {@link ErrorHandler} to invoke in case an exception is thrown
	 * from a listener.
	 * <p>Default is none, with a listener exception stopping the current
	 * multicast and getting propagated to the publisher of the current event.
	 * If a {@linkplain #setTaskExecutor task executor} is specified, each
	 * individual listener exception will get propagated to the executor but
	 * won't necessarily stop execution of other listeners.
	 * <p>Consider setting an {@link ErrorHandler} implementation that catches
	 * and logs exceptions (a la
	 * {@link org.springframework.scheduling.support.TaskUtils#LOG_AND_SUPPRESS_ERROR_HANDLER})
	 * or an implementation that logs exceptions while nevertheless propagating them
	 * (for example, {@link org.springframework.scheduling.support.TaskUtils#LOG_AND_PROPAGATE_ERROR_HANDLER}).
	 * @since 4.1
	 */
	public void setErrorHandler(@Nullable ErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
	}

	/**
	 * Return the current error handler for this multicaster.
	 * @since 4.1
	 */
	protected @Nullable ErrorHandler getErrorHandler() {
		return this.errorHandler;
	}

	@Override
	public void multicastEvent(ApplicationEvent event) {
		multicastEvent(event, null);
	}

	@Override
	public void multicastEvent(ApplicationEvent event, @Nullable ResolvableType eventType) {
		// 1. 解析事件类型
		// ResolvableType 是 Spring 的泛型封装工具。
		// 如果调用方没传类型，就从 event 实例本身解析。这个类型将用于后续过滤监听器。
		ResolvableType type = (eventType != null ? eventType : ResolvableType.forInstance(event));

		// 2. 获取任务执行器 (Executor)
		// 这是判断是否“异步广播”的第一要素。
		// 默认情况下它是 null（即同步广播）。如果你手动给 Multicaster 设置了线程池，这里就不为 null。
		Executor executor = getTaskExecutor();

		// 3. 【核心路由】查找匹配的监听器
		// getApplicationListeners 负责去通讯录（ListenerRegistry）里查。
		// 它会根据事件类型 (type) 筛选出所有关注该事件的监听器 (Observer)。
		// 这个方法内部有很强的缓存机制 (ListenerRetriever)，第一次匹配慢，后面都很快。
		for (ApplicationListener<?> listener : getApplicationListeners(event, type)) {

			// 4. 决策：同步执行还是异步执行？
			// 要满足两个条件才能异步：
			// A. 必须配置了线程池 (executor != null)。
			// B. 监听器本身必须支持异步 (listener.supportsAsyncExecution())。
			//    (注：supportsAsyncExecution 是 Spring 6.1+ 新增的接口方法，默认返回 true，
			//     但这允许某些特殊的监听器强制要求同步执行，即使配置了线程池。)
			if (executor != null && listener.supportsAsyncExecution()) {
				try {
					// 5. 【异步分支】
					// 将任务提交给线程池。
					// 此时，发布事件的主线程（业务线程）不会阻塞，会立即继续循环或返回。
					executor.execute(() -> invokeListener(listener, event));
				}
				catch (RejectedExecutionException ex) {
					// Probably on shutdown -> invoke listener locally instead
					// 6. 容错降级 (Fallback)
					// 如果线程池满了（抛出 RejectedExecutionException），或者正在关闭，
					// 为了保证事件肯定能送达，这里会“降级”为由当前线程同步执行。
					invokeListener(listener, event);
				}
			}
			else {
				// 7. 【同步分支】(默认情况)
				// 没有配置线程池，或者监听器强制要求同步。
				// 直接在当前线程调用 invokeListener。
				// 这意味着：publishEvent() 方法会阻塞，直到所有同步监听器都执行完毕。
				invokeListener(listener, event);
			}
		}
	}

	/**
	 * Invoke the given listener with the given event.
	 * @param listener the ApplicationListener to invoke
	 * @param event the current event to propagate
	 * @since 4.1
	 */
	protected void invokeListener(ApplicationListener<?> listener, ApplicationEvent event) {
		// 处理异常的一层包装，防止监听器报错导致整个流程中断
		ErrorHandler errorHandler = getErrorHandler();
		if (errorHandler != null) {
			try {
				doInvokeListener(listener, event);
			}
			catch (Throwable err) {
				errorHandler.handleError(err);
			}
		}
		else {
			doInvokeListener(listener, event);
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void doInvokeListener(ApplicationListener listener, ApplicationEvent event) {
		try {
			// 【最终调用】直接调用接口方法
			listener.onApplicationEvent(event);
		}
		catch (ClassCastException ex) {
			String msg = ex.getMessage();
			if (msg == null || matchesClassCastMessage(msg, event.getClass()) ||
					(event instanceof PayloadApplicationEvent payloadEvent &&
							matchesClassCastMessage(msg, payloadEvent.getPayload().getClass()))) {
				// Possibly a lambda-defined listener which we could not resolve the generic event type for
				// -> let's suppress the exception.
				Log loggerToUse = this.lazyLogger;
				if (loggerToUse == null) {
					loggerToUse = LogFactory.getLog(getClass());
					this.lazyLogger = loggerToUse;
				}
				if (loggerToUse.isTraceEnabled()) {
					loggerToUse.trace("Non-matching event type for listener: " + listener, ex);
				}
			}
			else {
				throw ex;
			}
		}
	}

	private boolean matchesClassCastMessage(String classCastMessage, Class<?> eventClass) {
		// On Java 8, the message starts with the class name: "java.lang.String cannot be cast..."
		if (classCastMessage.startsWith(eventClass.getName())) {
			return true;
		}
		// On Java 11, the message starts with "class ..." a.k.a. Class.toString()
		if (classCastMessage.startsWith(eventClass.toString())) {
			return true;
		}
		// On Java 9, the message used to contain the module name: "java.base/java.lang.String cannot be cast..."
		int moduleSeparatorIndex = classCastMessage.indexOf('/');
		if (moduleSeparatorIndex != -1 && classCastMessage.startsWith(eventClass.getName(), moduleSeparatorIndex + 1)) {
			return true;
		}
		// Assuming an unrelated class cast failure...
		return false;
	}

}
