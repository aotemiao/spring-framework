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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;

import org.springframework.core.GenericTypeResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.core.log.LogFormatUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.SmartHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UrlPathHelper;

/**
 * Extends {@link AbstractMessageConverterMethodArgumentResolver} with the ability to handle method
 * return values by writing to the response with {@link HttpMessageConverter HttpMessageConverters}.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Brian Clozel
 * @author Juergen Hoeller
 * @author Sebastien Deleuze
 * @since 3.1
 */
public abstract class AbstractMessageConverterMethodProcessor extends AbstractMessageConverterMethodArgumentResolver
		implements HandlerMethodReturnValueHandler {

	/* Extensions associated with the built-in message converters */
	private static final Set<String> SAFE_EXTENSIONS = Set.of(
			"txt", "text", "yml", "properties", "csv",
			"json", "xml", "atom", "rss",
			"png", "jpe", "jpeg", "jpg", "gif", "wbmp", "bmp");

	private static final Set<String> SAFE_MEDIA_BASE_TYPES =
			Set.of("audio", "image", "video");

	private static final List<MediaType> ALL_APPLICATION_MEDIA_TYPES =
			List.of(MediaType.ALL, new MediaType("application"));

	private static final List<MediaType> PROBLEM_MEDIA_TYPES =
			List.of(MediaType.APPLICATION_PROBLEM_JSON, MediaType.APPLICATION_PROBLEM_XML);

	private static final Type RESOURCE_REGION_LIST_TYPE =
			new ParameterizedTypeReference<List<ResourceRegion>>() {}.getType();


	private final ContentNegotiationManager contentNegotiationManager;

	private final List<ErrorResponse.Interceptor> errorResponseInterceptors = new ArrayList<>();

	private final Set<String> safeExtensions = new HashSet<>();


	/**
	 * Construct with the provided list of converters only.
	 */
	protected AbstractMessageConverterMethodProcessor(List<HttpMessageConverter<?>> converters) {
		this(converters, null, null);
	}

	/**
	 * Construct with the provided list of converters and {@link ContentNegotiationManager}.
	 */
	protected AbstractMessageConverterMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable ContentNegotiationManager contentNegotiationManager) {

		this(converters, contentNegotiationManager, null);
	}

	/**
	 * Variant of {@link #AbstractMessageConverterMethodProcessor(List, ContentNegotiationManager)}
	 * with an additional {@code requestResponseBodyAdvice} list for return value handling.
	 */
	protected AbstractMessageConverterMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable ContentNegotiationManager manager, @Nullable List<Object> requestResponseBodyAdvice) {

		this(converters, manager, requestResponseBodyAdvice, Collections.emptyList());
	}

	/**
	 * Variant of {@link #AbstractMessageConverterMethodProcessor(List, ContentNegotiationManager, List)}
	 * with additional list of {@link ErrorResponse.Interceptor}s for return value handling.
	 * @since 6.2
	 */
	protected AbstractMessageConverterMethodProcessor(List<HttpMessageConverter<?>> converters,
			@Nullable ContentNegotiationManager manager, @Nullable List<Object> requestResponseBodyAdvice,
			List<ErrorResponse.Interceptor> interceptors) {

		super(converters, requestResponseBodyAdvice);

		this.contentNegotiationManager = (manager != null ? manager : new ContentNegotiationManager());
		this.safeExtensions.addAll(this.contentNegotiationManager.getAllFileExtensions());
		this.safeExtensions.addAll(SAFE_EXTENSIONS);
		this.errorResponseInterceptors.addAll(interceptors);
	}


	/**
	 * Create a new {@link HttpOutputMessage} from the given {@link NativeWebRequest}.
	 * @param webRequest the web request to create an output message from
	 * @return the output message
	 */
	protected ServletServerHttpResponse createOutputMessage(NativeWebRequest webRequest) {
		HttpServletResponse response = webRequest.getNativeResponse(HttpServletResponse.class);
		Assert.state(response != null, "No HttpServletResponse");
		return new ServletServerHttpResponse(response);
	}

	/**
	 * Invoke the configured {@link ErrorResponse.Interceptor}'s.
	 * @since 6.2
	 */
	protected void invokeErrorResponseInterceptors(ProblemDetail detail, @Nullable ErrorResponse errorResponse) {
		try {
			for (ErrorResponse.Interceptor handler : this.errorResponseInterceptors) {
				handler.handleError(detail, errorResponse);
			}
		}
		catch (Throwable ex) {
			// ignore
		}
	}

	/**
	 * Writes the given return value to the given web request. Delegates to
	 * {@link #writeWithMessageConverters(Object, MethodParameter, ServletServerHttpRequest, ServletServerHttpResponse)}
	 */
	protected <T> void writeWithMessageConverters(T value, MethodParameter returnType, NativeWebRequest webRequest)
			throws IOException, HttpMediaTypeNotAcceptableException, HttpMessageNotWritableException {

		ServletServerHttpRequest inputMessage = createInputMessage(webRequest);
		ServletServerHttpResponse outputMessage = createOutputMessage(webRequest);
		writeWithMessageConverters(value, returnType, inputMessage, outputMessage);
	}

	/**
	 * Write the given return type to the given output message.
	 * @param value the value to write to the output message
	 * @param returnType the type of the value
	 * @param inputMessage the input messages, used to inspect the {@code Accept} header
	 * @param outputMessage the output message to write to
	 * @throws IOException thrown in case of I/O errors
	 * @throws HttpMediaTypeNotAcceptableException thrown when the conditions indicated
	 * by the {@code Accept} header on the request cannot be met by the message converters
	 * @throws HttpMessageNotWritableException thrown if a given message cannot
	 * be written by a converter, or if the content-type chosen by the server
	 * has no compatible converter.
	 */
	@SuppressWarnings({"rawtypes", "unchecked", "NullAway"})
	protected <T> void writeWithMessageConverters(@Nullable T value, MethodParameter returnType,
			ServletServerHttpRequest inputMessage, ServletServerHttpResponse outputMessage)
			throws IOException, HttpMediaTypeNotAcceptableException, HttpMessageNotWritableException {

		// ---------------------------------------------------------
		// 1. 确定返回值的类型 (Body & Type)
		// ---------------------------------------------------------
		Object body;
		Class<?> valueType;
		Type targetType;

		// 特殊处理字符串类型：
		// 如果返回值是 CharSequence (String, StringBuilder 等)，直接当作 String 处理
		if (value instanceof CharSequence) {
			body = value.toString();
			valueType = String.class;
			targetType = String.class;
		}
		else {
			body = value;
			// 获取实际运行时的类 (如果是 null，后续逻辑会处理)
			valueType = getReturnValueType(body, returnType);
			// 解析泛型类型 (例如 List<User>)，这对 Jackson 正确序列化泛型集合至关重要
			targetType = GenericTypeResolver.resolveType(getGenericType(returnType), returnType.getContainingClass());
		}

		// ---------------------------------------------------------
		// 2. 处理资源文件的断点续传 (Range Requests)
		// ---------------------------------------------------------
		// 如果返回值是 Resource 类型 (如 FileSystemResource)，并且支持 InputStream
		if (isResourceType(value, returnType)) {
			// 告知客户端：我支持按字节范围请求 (断点续传的关键头)
			outputMessage.getHeaders().set(HttpHeaders.ACCEPT_RANGES, "bytes");

			// 如果请求头包含 "Range" (例如 Range: bytes=0-1023) 且当前状态码是 200
			if (value != null && inputMessage.getHeaders().getFirst(HttpHeaders.RANGE) != null &&
					outputMessage.getServletResponse().getStatus() == 200) {
				Resource resource = (Resource) value;
				try {
					// 解析 Range 头
					List<HttpRange> httpRanges = inputMessage.getHeaders().getRange();
					// 将状态码改为 206 Partial Content
					outputMessage.getServletResponse().setStatus(HttpStatus.PARTIAL_CONTENT.value());
					// 将 Resource 切割成 ResourceRegion (区域片段) 作为新的 Body
					body = HttpRange.toResourceRegions(httpRanges, resource);
					valueType = body.getClass();
					targetType = RESOURCE_REGION_LIST_TYPE;
				}
				catch (IllegalArgumentException ex) {
					// 如果 Range 格式错误，返回 416 Requested Range Not Satisfiable
					outputMessage.getHeaders().set(HttpHeaders.CONTENT_RANGE, "bytes */" + resource.contentLength());
					outputMessage.getServletResponse().setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
				}
			}
		}

		// ---------------------------------------------------------
		// 3. 内容协商 (Content Negotiation) - 确定 MediaType
		// ---------------------------------------------------------
		MediaType selectedMediaType = null;
		MediaType contentType = outputMessage.getHeaders().getContentType();
		// 检查是否已经在 Response Header 中预设了 Content-Type
		// (例如在 Controller 中手动设置了 response.setContentType("application/json"))
		boolean isContentTypePreset = contentType != null && contentType.isConcrete();

		if (isContentTypePreset) {
			// 如果预设了，直接使用，跳过协商过程
			if (logger.isDebugEnabled()) {
				logger.debug("Found 'Content-Type:" + contentType + "' in response");
			}
			selectedMediaType = contentType;
		}
		else {
			// --- 开始自动协商 ---
			HttpServletRequest request = inputMessage.getServletRequest();
			List<MediaType> acceptableTypes;
			try {
				// A. 获取客户端“想要”的类型 (读取 Accept Header)
				// 例如: application/json, text/html
				acceptableTypes = getAcceptableMediaTypes(request);
			}
			catch (HttpMediaTypeNotAcceptableException ex) {
				// 如果 Accept 头解析失败，且是错误响应或空 Body，则忽略；否则抛出异常
				int series = outputMessage.getServletResponse().getStatus() / 100;
				if (body == null || series == 4 || series == 5) {
					if (logger.isDebugEnabled()) {
						logger.debug("Ignoring error response content (if any). " + ex);
					}
					return;
				}
				throw ex;
			}

			// B. 获取服务器“能给”的类型 (Producible Media Types)
			// 遍历所有 MessageConverter，问它们谁能处理这个 valueType
			List<MediaType> producibleTypes = getProducibleMediaTypes(request, valueType, targetType);

			// 如果有 Body 但找不到任何能处理的 Converter，抛出异常 (500)
			if (body != null && producibleTypes.isEmpty()) {
				throw new HttpMessageNotWritableException(
						"No converter found for return value of type: " + valueType);
			}

			// C. 计算交集：【想要的】 ∩ 【能给的】
			List<MediaType> compatibleMediaTypes = new ArrayList<>();
			determineCompatibleMediaTypes(acceptableTypes, producibleTypes, compatibleMediaTypes);

			// For ProblemDetail, fall back on RFC 9457 format
			// 特殊处理 Spring 6 的 ProblemDetail (RFC 9457 错误详情)
			// 如果交集为空，但返回的是 ProblemDetail，尝试回退到标准错误格式
			if (compatibleMediaTypes.isEmpty() && ProblemDetail.class.isAssignableFrom(valueType)) {
				determineCompatibleMediaTypes(PROBLEM_MEDIA_TYPES, producibleTypes, compatibleMediaTypes);
			}

			// 如果交集为空，说明无法满足客户端要求，抛出 406 Not Acceptable
			if (compatibleMediaTypes.isEmpty()) {
				if (logger.isDebugEnabled()) {
					logger.debug("No match for " + acceptableTypes + ", supported: " + producibleTypes);
				}
				if (body != null) {
					throw new HttpMediaTypeNotAcceptableException(producibleTypes);
				}
				return;
			}

			// D. 排序：根据具体程度和权重排序 (Quality Value, q=0.8)
			MimeTypeUtils.sortBySpecificity(compatibleMediaTypes);

			// E. 选择最佳匹配：取排序后的第一个具体的类型
			for (MediaType mediaType : compatibleMediaTypes) {
				if (mediaType.isConcrete()) {
					selectedMediaType = mediaType;
					break;
				}
				// 如果匹配到的是通配符 */* 或者 application/*，默认选 application/octet-stream
				else if (mediaType.isPresentIn(ALL_APPLICATION_MEDIA_TYPES)) {
					selectedMediaType = MediaType.APPLICATION_OCTET_STREAM;
					break;
				}
			}

			if (logger.isDebugEnabled()) {
				logger.debug("Using '" + selectedMediaType + "', given " +
						acceptableTypes + " and supported " + producibleTypes);
			}
		}

		// ---------------------------------------------------------
		// 4. 选择 Converter 并写入 (Write)
		// ---------------------------------------------------------
		if (selectedMediaType != null) {
			// 移除 quality value (例如 application/json;q=0.8 -> application/json)
			selectedMediaType = selectedMediaType.removeQualityValue();

			ResolvableType targetResolvableType = null;

			// 再次遍历所有的 Converter，找到那个能用 selectedMediaType 写入 valueType 的人
			for (HttpMessageConverter converter : this.messageConverters) {
				ConverterType converterTypeToUse = null;

				// 判断 Converter 类型并检查 canWrite 能力
				if (converter instanceof GenericHttpMessageConverter genericConverter) {
					// 泛型 Converter (如 Jackson)
					if (genericConverter.canWrite(targetType, valueType, selectedMediaType)) {
						converterTypeToUse = ConverterType.GENERIC;
					}
				}
				else if (converter instanceof SmartHttpMessageConverter smartConverter) {
					// 智能 Converter
					targetResolvableType = getNestedTypeIfNeeded(ResolvableType.forType(targetType));
					if (smartConverter.canWrite(targetResolvableType, valueType, selectedMediaType)) {
						converterTypeToUse = ConverterType.SMART;
					}
				}
				else if (converter.canWrite(valueType, selectedMediaType)){
					// 普通 Converter
					converterTypeToUse = ConverterType.BASE;
				}

				// 找到了合适的 Converter！
				if (converterTypeToUse != null) {
					// A. 执行 ResponseBodyAdvice 的 beforeBodyWrite 方法
					// 这是一个重要的扩展点！允许用户在写入前修改 Body (例如统一包装 Result<T>)
					body = getAdvice().beforeBodyWrite(body, returnType, selectedMediaType,
							(Class<? extends HttpMessageConverter<?>>) converter.getClass(), inputMessage, outputMessage);

					if (body != null) {
						Object theBody = body;
						LogFormatUtils.traceDebug(logger, traceOn ->
								"Writing [" + LogFormatUtils.formatValue(theBody, !traceOn) + "]");

						// 添加 Content-Disposition 头 (如果是下载文件)
						addContentDispositionHeader(inputMessage, outputMessage);

						// B. 【核心动作】执行写入
						switch (converterTypeToUse) {
							case BASE -> converter.write(body, selectedMediaType, outputMessage);
							case GENERIC -> ((GenericHttpMessageConverter) converter).write(body, targetType, selectedMediaType, outputMessage);
							case SMART -> ((SmartHttpMessageConverter) converter).write(body, targetResolvableType,
									selectedMediaType, outputMessage, getAdvice().determineWriteHints(body, returnType,
											selectedMediaType, (Class<? extends HttpMessageConverter<?>>) converter.getClass()));
						}
					}
					else {
						if (logger.isDebugEnabled()) {
							logger.debug("Nothing to write: null body");
						}
					}
					// 写入完成，直接返回
					return;
				}
			}
		}

		// ---------------------------------------------------------
		// 5. 错误兜底
		// ---------------------------------------------------------
		// 如果 Body 不为空，但代码走到这里，说明没找到 Converter 或者协商失败
		if (body != null) {
			Set<MediaType> producibleMediaTypes =
					(Set<MediaType>) inputMessage.getServletRequest()
							.getAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);

			// 如果 Content-Type 是预设的，但找不到 Converter，抛 500
			if (isContentTypePreset || !CollectionUtils.isEmpty(producibleMediaTypes)) {
				throw new HttpMessageNotWritableException(
						"No converter for [" + valueType + "] with preset Content-Type '" + contentType + "'");
			}
			// 否则抛 406 Not Acceptable
			throw new HttpMediaTypeNotAcceptableException(getSupportedMediaTypes(body.getClass()));
		}
	}

	/**
	 * Return the type of the value to be written to the response. Typically this is
	 * a simple check via getClass on the value but if the value is null, then the
	 * return type needs to be examined possibly including generic type determination
	 * (for example, {@code ResponseEntity<T>}).
	 */
	protected Class<?> getReturnValueType(@Nullable Object value, MethodParameter returnType) {
		return (value != null ? value.getClass() : returnType.getParameterType());
	}

	/**
	 * Return whether the returned value or the declared return type extends {@link Resource}.
	 */
	protected boolean isResourceType(@Nullable Object value, MethodParameter returnType) {
		Class<?> clazz = getReturnValueType(value, returnType);
		return clazz != InputStreamResource.class && Resource.class.isAssignableFrom(clazz);
	}

	/**
	 * Return the generic type of the {@code returnType} (or of the nested type
	 * if it is an {@link HttpEntity}).
	 */
	private Type getGenericType(MethodParameter returnType) {
		if (HttpEntity.class.isAssignableFrom(returnType.getParameterType())) {
			return ResolvableType.forType(returnType.getGenericParameterType()).getGeneric().getType();
		}
		else {
			return returnType.getGenericParameterType();
		}
	}

	/**
	 * Returns the media types that can be produced.
	 * @see #getProducibleMediaTypes(HttpServletRequest, Class, Type)
	 */
	@SuppressWarnings("unused")
	protected List<MediaType> getProducibleMediaTypes(HttpServletRequest request, Class<?> valueClass) {
		return getProducibleMediaTypes(request, valueClass, null);
	}

	/**
	 * Returns the media types that can be produced. The resulting media types are:
	 * <ul>
	 * <li>The producible media types specified in the request mappings, or
	 * <li>Media types of configured converters that can write the specific return value, or
	 * <li>{@link MediaType#ALL}
	 * </ul>
	 * @since 4.2
	 */
	@SuppressWarnings("unchecked")
	protected List<MediaType> getProducibleMediaTypes(
			HttpServletRequest request, Class<?> valueClass, @Nullable Type targetType) {

		Set<MediaType> mediaTypes =
				(Set<MediaType>) request.getAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
		if (!CollectionUtils.isEmpty(mediaTypes)) {
			return new ArrayList<>(mediaTypes);
		}
		Set<MediaType> result = new LinkedHashSet<>();
		for (HttpMessageConverter<?> converter : this.messageConverters) {
			if (converter instanceof GenericHttpMessageConverter<?> genericConverter && targetType != null) {
				if (genericConverter.canWrite(targetType, valueClass, null)) {
					result.addAll(converter.getSupportedMediaTypes(valueClass));
				}
			}
			else if (converter instanceof SmartHttpMessageConverter<?> smartConverter && targetType != null) {
				ResolvableType resolvableType = ResolvableType.forType(targetType);
				if (smartConverter.canWrite(resolvableType, valueClass, null)) {
					result.addAll(converter.getSupportedMediaTypes(valueClass));
				}
			}
			else if (converter.canWrite(valueClass, null)) {
				result.addAll(converter.getSupportedMediaTypes(valueClass));
			}
		}
		return (result.isEmpty() ? Collections.singletonList(MediaType.ALL) : new ArrayList<>(result));
	}

	private List<MediaType> getAcceptableMediaTypes(HttpServletRequest request)
			throws HttpMediaTypeNotAcceptableException {

		return this.contentNegotiationManager.resolveMediaTypes(new ServletWebRequest(request));
	}

	private void determineCompatibleMediaTypes(
			List<MediaType> acceptableTypes, List<MediaType> producibleTypes, List<MediaType> mediaTypesToUse) {

		for (MediaType requestedType : acceptableTypes) {
			for (MediaType producibleType : producibleTypes) {
				if (requestedType.isCompatibleWith(producibleType)) {
					mediaTypesToUse.add(getMostSpecificMediaType(requestedType, producibleType));
				}
			}
		}
	}

	/**
	 * Return the more specific of the acceptable and the producible media types
	 * with the q-value of the former.
	 */
	private MediaType getMostSpecificMediaType(MediaType acceptType, MediaType produceType) {
		MediaType produceTypeToUse = produceType.copyQualityValue(acceptType);
		if (acceptType.isLessSpecific(produceTypeToUse)) {
			return produceTypeToUse;
		}
		else {
			return acceptType;
		}
	}

	/**
	 * Check if the path has a file extension and whether the extension is either
	 * on the list of {@link #SAFE_EXTENSIONS safe extensions} or explicitly
	 * {@link ContentNegotiationManager#getAllFileExtensions() registered}.
	 * If not, and the status is in the 2xx range, a 'Content-Disposition'
	 * header with a safe attachment file name ("f.txt") is added to prevent
	 * RFD exploits.
	 */
	private void addContentDispositionHeader(ServletServerHttpRequest request, ServletServerHttpResponse response) {
		HttpHeaders headers = response.getHeaders();
		if (headers.containsHeader(HttpHeaders.CONTENT_DISPOSITION)) {
			return;
		}

		try {
			int status = response.getServletResponse().getStatus();
			if (status < 200 || (status > 299 && status < 400)) {
				return;
			}
		}
		catch (Throwable ex) {
			// ignore
		}

		HttpServletRequest servletRequest = request.getServletRequest();
		String requestUri = UrlPathHelper.rawPathInstance.getOriginatingRequestUri(servletRequest);

		int index = requestUri.lastIndexOf('/') + 1;
		String filename = requestUri.substring(index);
		String pathParams = "";

		index = filename.indexOf(';');
		if (index != -1) {
			pathParams = filename.substring(index);
			filename = filename.substring(0, index);
		}

		filename = UrlPathHelper.defaultInstance.decodeRequestString(servletRequest, filename);
		String ext = StringUtils.getFilenameExtension(filename);

		pathParams = UrlPathHelper.defaultInstance.decodeRequestString(servletRequest, pathParams);
		String extInPathParams = StringUtils.getFilenameExtension(pathParams);

		if (!safeExtension(servletRequest, ext) || !safeExtension(servletRequest, extInPathParams)) {
			headers.add(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=f.txt");
		}
	}

	@SuppressWarnings("unchecked")
	private boolean safeExtension(HttpServletRequest request, @Nullable String extension) {
		if (!StringUtils.hasText(extension)) {
			return true;
		}
		extension = extension.toLowerCase(Locale.ROOT);
		if (this.safeExtensions.contains(extension)) {
			return true;
		}
		String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		if (pattern != null && pattern.endsWith("." + extension)) {
			return true;
		}
		if (extension.equals("html")) {
			String name = HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE;
			Set<MediaType> mediaTypes = (Set<MediaType>) request.getAttribute(name);
			if (!CollectionUtils.isEmpty(mediaTypes) && mediaTypes.contains(MediaType.TEXT_HTML)) {
				return true;
			}
		}
		MediaType mediaType = resolveMediaType(request, extension);
		return (mediaType != null && (safeMediaType(mediaType)));
	}

	private @Nullable MediaType resolveMediaType(ServletRequest request, String extension) {
		MediaType result = null;
		String rawMimeType = request.getServletContext().getMimeType("file." + extension);
		if (StringUtils.hasText(rawMimeType)) {
			result = MediaType.parseMediaType(rawMimeType);
		}
		if (result == null || MediaType.APPLICATION_OCTET_STREAM.equals(result)) {
			result = MediaTypeFactory.getMediaType("file." + extension).orElse(null);
		}
		return result;
	}

	private boolean safeMediaType(MediaType mediaType) {
		return (SAFE_MEDIA_BASE_TYPES.contains(mediaType.getType()) ||
				mediaType.getSubtype().endsWith("+xml"));
	}

}
