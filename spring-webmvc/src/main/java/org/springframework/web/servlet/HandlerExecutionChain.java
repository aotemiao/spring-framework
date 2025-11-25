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

package org.springframework.web.servlet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

import org.springframework.util.CollectionUtils;

/**
 * Handler execution chain, consisting of handler object and any handler interceptors.
 * Returned by HandlerMapping's {@link HandlerMapping#getHandler} method.
 *
 * @author Juergen Hoeller
 * @since 20.06.2003
 * @see HandlerInterceptor
 */
public class HandlerExecutionChain {

	private static final Log logger = LogFactory.getLog(HandlerExecutionChain.class);

	private final Object handler;

	private final List<HandlerInterceptor> interceptorList = new ArrayList<>();

	private int interceptorIndex = -1;


	/**
	 * Create a new HandlerExecutionChain.
	 * @param handler the handler object to execute
	 */
	public HandlerExecutionChain(Object handler) {
		this(handler, (HandlerInterceptor[]) null);
	}

	/**
	 * Create a new HandlerExecutionChain.
	 * @param handler the handler object to execute
	 * @param interceptors the array of interceptors to apply
	 * (in the given order) before the handler itself executes
	 */
	public HandlerExecutionChain(Object handler, HandlerInterceptor @Nullable ... interceptors) {
		this(handler, (interceptors != null ? Arrays.asList(interceptors) : Collections.emptyList()));
	}

	/**
	 * Create a new HandlerExecutionChain.
	 * @param handler the handler object to execute
	 * @param interceptorList the list of interceptors to apply
	 * (in the given order) before the handler itself executes
	 * @since 5.3
	 */
	public HandlerExecutionChain(Object handler, List<HandlerInterceptor> interceptorList) {
		if (handler instanceof HandlerExecutionChain originalChain) {
			this.handler = originalChain.getHandler();
			this.interceptorList.addAll(originalChain.interceptorList);
		}
		else {
			this.handler = handler;
		}
		this.interceptorList.addAll(interceptorList);
	}


	/**
	 * Return the handler object to execute.
	 */
	public Object getHandler() {
		return this.handler;
	}

	/**
	 * Add the given interceptor to the end of this chain.
	 */
	public void addInterceptor(HandlerInterceptor interceptor) {
		this.interceptorList.add(interceptor);
	}

	/**
	 * Add the given interceptor at the specified index of this chain.
	 * @since 5.2
	 */
	public void addInterceptor(int index, HandlerInterceptor interceptor) {
		this.interceptorList.add(index, interceptor);
	}

	/**
	 * Add the given interceptors to the end of this chain.
	 */
	public void addInterceptors(HandlerInterceptor... interceptors) {
		CollectionUtils.mergeArrayIntoCollection(interceptors, this.interceptorList);
	}

	/**
	 * Return the array of interceptors to apply (in the given order).
	 * @return the array of HandlerInterceptors instances (may be {@code null})
	 */
	public HandlerInterceptor @Nullable [] getInterceptors() {
		return (!this.interceptorList.isEmpty() ? this.interceptorList.toArray(new HandlerInterceptor[0]) : null);
	}

	/**
	 * Return the list of interceptors to apply (in the given order).
	 * @return the list of HandlerInterceptors instances (potentially empty)
	 * @since 5.3
	 */
	public List<HandlerInterceptor> getInterceptorList() {
		return (!this.interceptorList.isEmpty() ? Collections.unmodifiableList(this.interceptorList) :
				Collections.emptyList());
	}


	/**
	 * Apply preHandle methods of registered interceptors.
	 * @return {@code true} if the execution chain should proceed with the
	 * next interceptor or the handler itself. Else, DispatcherServlet assumes
	 * that this interceptor has already dealt with the response itself.
	 */
	boolean applyPreHandle(HttpServletRequest request, HttpServletResponse response) throws Exception {
		// 1. 【正序遍历】从第 0 个开始，一个一个执行
		for (int i = 0; i < this.interceptorList.size(); i++) {
			HandlerInterceptor interceptor = this.interceptorList.get(i);
			// 2. 执行 preHandle
			if (!interceptor.preHandle(request, response, this.handler)) {
				// 3. 【关键】如果返回 false (拦截)
				// 立即触发“已执行”拦截器的收尾工作
				triggerAfterCompletion(request, response, null);
				return false;
			}
			// 4. 记录当前执行到了第几个拦截器
			// this.interceptorIndex 是一个成员变量，非常重要！
			this.interceptorIndex = i;
		}
		// 全部通过
		return true;
	}

	/**
	 * Apply postHandle methods of registered interceptors.
	 */
	void applyPostHandle(HttpServletRequest request, HttpServletResponse response, @Nullable ModelAndView mv)
			throws Exception {

		// 1. 【倒序遍历】从最后一个拦截器往前执行
		for (int i = this.interceptorList.size() - 1; i >= 0; i--) {
			HandlerInterceptor interceptor = this.interceptorList.get(i);
			// 2. 执行 postHandle
			interceptor.postHandle(request, response, this.handler, mv);
		}
	}

	/**
	 * Trigger afterCompletion callbacks on the mapped HandlerInterceptors.
	 * Will just invoke afterCompletion for all interceptors whose preHandle invocation
	 * has successfully completed and returned true.
	 */
	void triggerAfterCompletion(HttpServletRequest request, HttpServletResponse response, @Nullable Exception ex) {
		// 1. 【核心细节】倒序遍历 (Reverse Iteration)
		// 这里的 i 是从 this.interceptorIndex 开始的，而不是 list.size() - 1。
		// interceptorIndex 是一个状态变量，它记录了之前有多少个拦截器的 preHandle 方法成功返回了 true。
		//
		// 为什么要倒序？(LIFO - 后进先出)
		// 类似于栈结构。如果 preHandle 的执行顺序是 A -> B -> C
		// 那么收尾工作的 afterCompletion 执行顺序必须是 C -> B -> A
		// 只有这样才能保证资源释放的对称性（比如 B 打开了一个连接，应该由 B 在 C 之后关闭）。
		for (int i = this.interceptorIndex; i >= 0; i--) {
			HandlerInterceptor interceptor = this.interceptorList.get(i);
			try {
				// 2. 执行清理逻辑
				// 传入参数 ex：如果请求过程中发生了未捕获的异常，这里会拿到该异常对象；如果是正常结束，ex 为 null。
				// 拦截器可以在这里记录异常日志，或者清理 ThreadLocal 变量。
				interceptor.afterCompletion(request, response, this.handler, ex);
			}
			catch (Throwable ex2) {
				// 3. 【容错处理 - 吞掉异常】
				// 这里的 try-catch 至关重要！
				// 如果某个拦截器（比如 C）的清理逻辑抛出了异常，我们只记录错误日志，绝对不能抛出异常来中断循环。
				// 必须保证链条中剩下的其他拦截器（B 和 A）依然有机会执行它们的清理逻辑。
				// 这体现了 "Best Effort" (尽最大努力) 的清理原则。
				logger.error("HandlerInterceptor.afterCompletion threw exception", ex2);
			}
		}
	}

	/**
	 * Apply afterConcurrentHandlerStarted callback on mapped AsyncHandlerInterceptors.
	 */
	void applyAfterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response) {
		for (int i = this.interceptorList.size() - 1; i >= 0; i--) {
			HandlerInterceptor interceptor = this.interceptorList.get(i);
			if (interceptor instanceof AsyncHandlerInterceptor asyncInterceptor) {
				try {
					asyncInterceptor.afterConcurrentHandlingStarted(request, response, this.handler);
				}
				catch (Throwable ex) {
					if (logger.isErrorEnabled()) {
						logger.error("Interceptor [" + interceptor + "] failed in afterConcurrentHandlingStarted", ex);
					}
				}
			}
		}
	}


	/**
	 * Delegates to the handler's {@code toString()} implementation.
	 */
	@Override
	public String toString() {
		return "HandlerExecutionChain with [" + getHandler() + "] and " + this.interceptorList.size() + " interceptors";
	}

}
