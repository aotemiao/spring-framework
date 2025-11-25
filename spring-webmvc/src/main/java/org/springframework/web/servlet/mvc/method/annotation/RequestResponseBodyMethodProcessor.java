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

package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.List;

import org.jspecify.annotations.Nullable;

import org.springframework.core.Conventions;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.validation.BindingResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;

/**
 * Resolves method arguments annotated with {@code @RequestBody} and handles return
 * values from methods annotated with {@code @ResponseBody} by reading and writing
 * to the body of the request or response with an {@link HttpMessageConverter}.
 *
 * <p>An {@code @RequestBody} method argument is also validated if it is annotated
 * with any
 * {@linkplain org.springframework.validation.annotation.ValidationAnnotationUtils#determineValidationHints
 * annotations that trigger validation}. In case of validation failure,
 * {@link MethodArgumentNotValidException} is raised and results in an HTTP 400
 * response status code if {@link DefaultHandlerExceptionResolver} is configured.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.1
 */
public class RequestResponseBodyMethodProcessor extends AbstractMessageConverterMethodProcessor {

	/**
	 * Basic constructor with converters only. Suitable for resolving
	 * {@code @RequestBody}. For handling {@code @ResponseBody} consider also
	 * providing a {@code ContentNegotiationManager}.
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters) {
		super(converters);
	}

	/**
	 * Basic constructor with converters and {@code ContentNegotiationManager}.
	 * Suitable for resolving {@code @RequestBody} and handling
	 * {@code @ResponseBody} without {@code Request~} or
	 * {@code ResponseBodyAdvice}.
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable ContentNegotiationManager manager) {

		super(converters, manager);
	}

	/**
	 * Complete constructor for resolving {@code @RequestBody} method arguments.
	 * For handling {@code @ResponseBody} consider also providing a
	 * {@code ContentNegotiationManager}.
	 * @since 4.2
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable List<Object> requestResponseBodyAdvice) {

		super(converters, null, requestResponseBodyAdvice);
	}

	/**
	 * Variant of {@link #RequestResponseBodyMethodProcessor(List, List)}
	 * with an additional {@link ContentNegotiationManager} argument, for return
	 * value handling.
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable ContentNegotiationManager manager, @Nullable List<Object> requestResponseBodyAdvice) {

		super(converters, manager, requestResponseBodyAdvice);
	}

	/**
	 * Variant of{@link #RequestResponseBodyMethodProcessor(List, ContentNegotiationManager, List)}
	 * with an additional {@link ErrorResponse.Interceptor} argument for return
	 * value handling.
	 * @since 6.2
	 */
	public RequestResponseBodyMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable ContentNegotiationManager manager, List<Object> requestResponseBodyAdvice,
			List<ErrorResponse.Interceptor> interceptors) {

		super(converters, manager, requestResponseBodyAdvice, interceptors);
	}


	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		// 解析加了 @RequestBody 注解的参数
		return parameter.hasParameterAnnotation(RequestBody.class);
	}

	@Override
	public boolean supportsReturnType(MethodParameter returnType) {
		// 1. 检查方法上有没有 @ResponseBody
		// 2. 或者类上有没有 @ResponseBody (比如 @RestController = @Controller + @ResponseBody)
		return (AnnotatedElementUtils.hasAnnotation(returnType.getContainingClass(), ResponseBody.class) ||
				returnType.hasMethodAnnotation(ResponseBody.class));
	}

	/**
	 * Throws MethodArgumentNotValidException if validation fails.
	 * @throws HttpMessageNotReadableException if {@link RequestBody#required()}
	 * is {@code true} and there is no body content or if there is no suitable
	 * converter to read the content with.
	 */
	@Override
	public @Nullable Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, @Nullable WebDataBinderFactory binderFactory) throws Exception {

		// 1. 处理 Optional 包装
		// 如果 Controller 的参数类型是 Optional<User>，这里会将其解包，
		// 让 parameter 指向内部的泛型类型 (User)。
		// 目的是为了让后续的 MessageConverter 能够识别真正的目标类型进行反序列化。
		parameter = parameter.nestedIfOptional();

		// 2. 【核心】读取 HTTP Body 并反序列化
		// 调用父类方法，根据 Content-Type (如 application/json) 选择合适的 HttpMessageConverter (如 Jackson)，
		// 读取请求流并将数据转换成 Java 对象 (arg)。
		Object arg = readWithMessageConverters(webRequest, parameter, parameter.getNestedGenericParameterType());

		// 3. 数据绑定与校验流程
		if (binderFactory != null) {

			// 获取参数名称 (例如 "user")，用于生成 BindingResult 的 Key
			String name = Conventions.getVariableNameForParameter(parameter);
			ResolvableType type = ResolvableType.forMethodParameter(parameter);

			// 创建 WebDataBinder (数据绑定器)
			// Binder 就像一个档案袋，它持有目标对象 (arg)、对象类型信息以及校验结果 (BindingResult)
			WebDataBinder binder = binderFactory.createBinder(webRequest, arg, name, type);

			if (arg != null) {
				// 4. 执行校验逻辑
				// 检查参数上是否有 @Valid 或 @Validated 注解。
				// 如果有，则触发 Validator (通常是 Hibernate Validator) 对 arg 进行校验。
				validateIfApplicable(binder, parameter);

				// 5. 决定是否抛出异常
				// binder.getBindingResult().hasErrors(): 检查是否有校验错误。
				// isBindExceptionRequired: 检查 Controller 方法参数列表中是否紧跟了一个 BindingResult 参数。
				// -> 如果没写 BindingResult (返回 true)，说明开发者不打算自己处理错误，Spring 就会抛出异常中断流程。
				// -> 如果写了 BindingResult (返回 false)，Spring 就不抛异常，而是把错误交给开发者处理。
				if (binder.getBindingResult().hasErrors() && isBindExceptionRequired(binder, parameter)) {
					// 抛出参数校验异常 (默认会被映射为 HTTP 400 Bad Request)
					throw new MethodArgumentNotValidException(parameter, binder.getBindingResult());
				}
			}

			// 6. 保存校验结果
			// 将 BindingResult 放入 Model 容器中。
			// 这样后续的拦截器或视图层可以通过 Key 获取校验详情。
			if (mavContainer != null) {
				mavContainer.addAttribute(BindingResult.MODEL_KEY_PREFIX + name, binder.getBindingResult());
			}
		}

		// 7. 适配返回值
		// 对应第 1 步的解包操作。
		// 如果原始参数是 Optional<User>，这里会将 arg 重新包装为 Optional 对象返回。
		// 如果原始参数是 User，则直接返回 arg。
		return adaptArgumentIfNecessary(arg, parameter);
	}

	@Override
	protected @Nullable Object readWithMessageConverters(NativeWebRequest webRequest, MethodParameter parameter,
			Type paramType) throws IOException, HttpMediaTypeNotSupportedException, HttpMessageNotReadableException {

		ServletServerHttpRequest inputMessage = createInputMessage(webRequest);
		Object arg = readWithMessageConverters(inputMessage, parameter, paramType);
		if (arg == null && checkRequired(parameter)) {
			throw new HttpMessageNotReadableException("Required request body is missing: " +
					parameter.getExecutable().toGenericString(), inputMessage);
		}
		return arg;
	}

	protected boolean checkRequired(MethodParameter parameter) {
		RequestBody requestBody = parameter.getParameterAnnotation(RequestBody.class);
		return (requestBody != null && requestBody.required() && !parameter.isOptional());
	}

	@Override
	public void handleReturnValue(@Nullable Object returnValue, MethodParameter returnType,
			ModelAndViewContainer mavContainer, NativeWebRequest webRequest)
			throws IOException, HttpMediaTypeNotAcceptableException, HttpMessageNotWritableException {

		// 1. 【关键】标记请求已处理 (Flag as Handled)
		// 这行代码至关重要！它告诉 ModelAndViewContainer：
		// “我已经处理完响应了（或者马上就要写完了），请不要再去找 ViewResolver 渲染视图了。”
		// 这就是为什么加了 @ResponseBody 就不会跳转 JSP/HTML 的根本原因。
		mavContainer.setRequestHandled(true);

		// 2. 包装 Request 和 Response
		// 将原生的 HttpServletRequest/Response 包装成 Spring 的 HttpInputMessage/HttpOutputMessage
		// 以便后续 MessageConverter 使用。
		ServletServerHttpRequest inputMessage = createInputMessage(webRequest);
		ServletServerHttpResponse outputMessage = createOutputMessage(webRequest);

		// 3. 【Spring 6 新特性】处理 ProblemDetail (RFC 7807 错误详情)
		// 如果 Controller 返回的是一个标准错误对象 ProblemDetail
		if (returnValue instanceof ProblemDetail detail) {
			// 3.1 同步状态码：把对象里的 status (如 404) 设置到 HTTP 响应头中
			outputMessage.setStatusCode(HttpStatusCode.valueOf(detail.getStatus()));

			// 3.2 自动填充 Instance URI
			// 如果开发者没填 instance 字段，默认将其设为当前请求的 URI
			if (detail.getInstance() == null) {
				URI path = URI.create(inputMessage.getServletRequest().getRequestURI());
				detail.setInstance(path);
			}

			// 3.3 触发错误拦截器 (ErrorResponseInterceptor)
			// 允许开发者对 ProblemDetail 进行全局的统一修改
			invokeErrorResponseInterceptors(detail, null);
		}

		// Try even with null return value. ResponseBodyAdvice could get involved.
		// 4. 【核心】调用消息转换器进行写入
		// 这里调用的是父类 AbstractMessageConverterMethodProcessor 的方法。
		// 注意：即使 returnValue 是 null，也要进来！
		// 原因：可能存在 ResponseBodyAdvice (切面)，它可能会拦截 null 并修改为非 null 的值，
		// 或者即使 Body 为空，也需要处理 Response Header。
		writeWithMessageConverters(returnValue, returnType, inputMessage, outputMessage);
	}

}
