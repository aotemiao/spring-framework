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

package org.springframework.http.converter.json;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.ser.FilterProvider;
import org.jspecify.annotations.Nullable;

import org.springframework.core.GenericTypeResolver;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.AbstractGenericHttpMessageConverter;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StreamUtils;
import org.springframework.util.TypeUtils;

/**
 * Abstract base class for Jackson based and content type independent
 * {@link HttpMessageConverter} implementations.
 *
 * @author Arjen Poutsma
 * @author Keith Donald
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @since 4.1
 * @see MappingJackson2HttpMessageConverter
 * @deprecated since 7.0 in favor of {@link AbstractJacksonHttpMessageConverter}
 */
@Deprecated(since = "7.0", forRemoval = true)
@SuppressWarnings("removal")
public abstract class AbstractJackson2HttpMessageConverter extends AbstractGenericHttpMessageConverter<Object> {

	private static final Map<String, JsonEncoding> ENCODINGS;

	static {
		ENCODINGS = CollectionUtils.newHashMap(JsonEncoding.values().length);
		for (JsonEncoding encoding : JsonEncoding.values()) {
			ENCODINGS.put(encoding.getJavaName(), encoding);
		}
		ENCODINGS.put("US-ASCII", JsonEncoding.UTF8);
	}


	// 核心对象
	protected ObjectMapper defaultObjectMapper;

	private @Nullable Map<Class<?>, Map<MediaType, ObjectMapper>> objectMapperRegistrations;

	private @Nullable Boolean prettyPrint;

	private final @Nullable PrettyPrinter ssePrettyPrinter;


	protected AbstractJackson2HttpMessageConverter(ObjectMapper objectMapper) {
		this.defaultObjectMapper = objectMapper;
		DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
		prettyPrinter.indentObjectsWith(new DefaultIndenter("  ", "\ndata:"));
		this.ssePrettyPrinter = prettyPrinter;
	}

	protected AbstractJackson2HttpMessageConverter(ObjectMapper objectMapper, MediaType supportedMediaType) {
		this(objectMapper);
		setSupportedMediaTypes(Collections.singletonList(supportedMediaType));
	}

	protected AbstractJackson2HttpMessageConverter(ObjectMapper objectMapper, MediaType... supportedMediaTypes) {
		this(objectMapper);
		setSupportedMediaTypes(Arrays.asList(supportedMediaTypes));
	}


	@Override
	public void setSupportedMediaTypes(List<MediaType> supportedMediaTypes) {
		super.setSupportedMediaTypes(supportedMediaTypes);
	}

	/**
	 * Configure the main {@code ObjectMapper} to use for Object conversion.
	 * If not set, a default {@link ObjectMapper} instance is created.
	 * <p>Setting a custom-configured {@code ObjectMapper} is one way to take
	 * further control of the JSON serialization process. For example, an extended
	 * {@link com.fasterxml.jackson.databind.ser.SerializerFactory}
	 * can be configured that provides custom serializers for specific types.
	 * Another option for refining the serialization process is to use Jackson's
	 * provided annotations on the types to be serialized, in which case a
	 * custom-configured ObjectMapper is unnecessary.
	 * @see #registerObjectMappersForType(Class, Consumer)
	 */
	public void setObjectMapper(ObjectMapper objectMapper) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		this.defaultObjectMapper = objectMapper;
		configurePrettyPrint();
	}

	/**
	 * Return the main {@code ObjectMapper} in use.
	 */
	public ObjectMapper getObjectMapper() {
		return this.defaultObjectMapper;
	}

	/**
	 * Configure the {@link ObjectMapper} instances to use for the given
	 * {@link Class}. This is useful when you want to deviate from the
	 * {@link #getObjectMapper() default} ObjectMapper or have the
	 * {@code ObjectMapper} vary by {@code MediaType}.
	 * <p><strong>Note:</strong> Use of this method effectively turns off use of
	 * the default {@link #getObjectMapper() ObjectMapper} and
	 * {@link #setSupportedMediaTypes(List) supportedMediaTypes} for the given
	 * class. Therefore it is important for the mappings configured here to
	 * {@link MediaType#includes(MediaType) include} every MediaType that must
	 * be supported for the given class.
	 * @param clazz the type of Object to register ObjectMapper instances for
	 * @param registrar a consumer to populate or otherwise update the
	 * MediaType-to-ObjectMapper associations for the given Class
	 * @since 5.3.4
	 */
	public void registerObjectMappersForType(Class<?> clazz, Consumer<Map<MediaType, ObjectMapper>> registrar) {
		if (this.objectMapperRegistrations == null) {
			this.objectMapperRegistrations = new LinkedHashMap<>();
		}
		Map<MediaType, ObjectMapper> registrations =
				this.objectMapperRegistrations.computeIfAbsent(clazz, c -> new LinkedHashMap<>());
		registrar.accept(registrations);
	}

	/**
	 * Return ObjectMapper registrations for the given class, if any.
	 * @param clazz the class to look up for registrations for
	 * @return a map with registered MediaType-to-ObjectMapper registrations,
	 * or empty if in case of no registrations for the given class.
	 * @since 5.3.4
	 */
	public Map<MediaType, ObjectMapper> getObjectMappersForType(Class<?> clazz) {
		for (Map.Entry<Class<?>, Map<MediaType, ObjectMapper>> entry : getObjectMapperRegistrations().entrySet()) {
			if (entry.getKey().isAssignableFrom(clazz)) {
				return entry.getValue();
			}
		}
		return Collections.emptyMap();
	}

	@Override
	public List<MediaType> getSupportedMediaTypes(Class<?> clazz) {
		List<MediaType> result = null;
		for (Map.Entry<Class<?>, Map<MediaType, ObjectMapper>> entry : getObjectMapperRegistrations().entrySet()) {
			if (entry.getKey().isAssignableFrom(clazz)) {
				result = (result != null ? result : new ArrayList<>(entry.getValue().size()));
				result.addAll(entry.getValue().keySet());
			}
		}
		if (!CollectionUtils.isEmpty(result)) {
			return result;
		}
		return (ProblemDetail.class.isAssignableFrom(clazz) ?
				getMediaTypesForProblemDetail() : getSupportedMediaTypes());
	}

	private Map<Class<?>, Map<MediaType, ObjectMapper>> getObjectMapperRegistrations() {
		return (this.objectMapperRegistrations != null ? this.objectMapperRegistrations : Collections.emptyMap());
	}

	/**
	 * Return the supported media type(s) for {@link ProblemDetail}.
	 * By default, an empty list, unless overridden in subclasses.
	 * @since 6.0.5
	 */
	protected List<MediaType> getMediaTypesForProblemDetail() {
		return Collections.emptyList();
	}

	/**
	 * Whether to use the {@link DefaultPrettyPrinter} when writing JSON.
	 * This is a shortcut for setting up an {@code ObjectMapper} as follows:
	 * <pre class="code">
	 * ObjectMapper mapper = new ObjectMapper();
	 * mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
	 * converter.setObjectMapper(mapper);
	 * </pre>
	 */
	public void setPrettyPrint(boolean prettyPrint) {
		this.prettyPrint = prettyPrint;
		configurePrettyPrint();
	}

	private void configurePrettyPrint() {
		if (this.prettyPrint != null) {
			this.defaultObjectMapper.configure(SerializationFeature.INDENT_OUTPUT, this.prettyPrint);
		}
	}


	@Override
	public boolean canRead(Class<?> clazz, @Nullable MediaType mediaType) {
		return canRead(clazz, null, mediaType);
	}

	@SuppressWarnings("deprecation")  // as of Jackson 2.18: can(De)Serialize
	@Override
	public boolean canRead(Type type, @Nullable Class<?> contextClass, @Nullable MediaType mediaType) {
		if (!canRead(mediaType)) {
			return false;
		}
		JavaType javaType = getJavaType(type, contextClass);
		ObjectMapper objectMapper = selectObjectMapper(javaType.getRawClass(), mediaType);
		if (objectMapper == null) {
			return false;
		}
		AtomicReference<Throwable> causeRef = new AtomicReference<>();
		if (objectMapper.canDeserialize(javaType, causeRef)) {
			return true;
		}
		logWarningIfNecessary(javaType, causeRef.get());
		return false;
	}

	@SuppressWarnings("deprecation")  // as of Jackson 2.18: can(De)Serialize
	@Override
	public boolean canWrite(Class<?> clazz, @Nullable MediaType mediaType) {
		if (!canWrite(mediaType)) {
			return false;
		}
		if (mediaType != null && mediaType.getCharset() != null) {
			Charset charset = mediaType.getCharset();
			if (!ENCODINGS.containsKey(charset.name())) {
				return false;
			}
		}
		ObjectMapper objectMapper = selectObjectMapper(clazz, mediaType);
		if (objectMapper == null) {
			return false;
		}
		AtomicReference<Throwable> causeRef = new AtomicReference<>();
		if (objectMapper.canSerialize(clazz, causeRef)) {
			return true;
		}
		logWarningIfNecessary(clazz, causeRef.get());
		return false;
	}

	/**
	 * Select an ObjectMapper to use, either the main ObjectMapper or another
	 * if the handling for the given Class has been customized through
	 * {@link #registerObjectMappersForType(Class, Consumer)}.
	 */
	private @Nullable ObjectMapper selectObjectMapper(Class<?> targetType, @Nullable MediaType targetMediaType) {
		if (targetMediaType == null || CollectionUtils.isEmpty(this.objectMapperRegistrations)) {
			return this.defaultObjectMapper;
		}
		for (Map.Entry<Class<?>, Map<MediaType, ObjectMapper>> typeEntry : getObjectMapperRegistrations().entrySet()) {
			if (typeEntry.getKey().isAssignableFrom(targetType)) {
				for (Map.Entry<MediaType, ObjectMapper> objectMapperEntry : typeEntry.getValue().entrySet()) {
					if (objectMapperEntry.getKey().includes(targetMediaType)) {
						return objectMapperEntry.getValue();
					}
				}
				// No matching registrations
				return null;
			}
		}
		// No registrations
		return this.defaultObjectMapper;
	}

	/**
	 * Determine whether to log the given exception coming from a
	 * {@link ObjectMapper#canDeserialize} / {@link ObjectMapper#canSerialize} check.
	 * @param type the class that Jackson tested for (de-)serializability
	 * @param cause the Jackson-thrown exception to evaluate
	 * (typically a {@link JsonMappingException})
	 * @since 4.3
	 */
	protected void logWarningIfNecessary(Type type, @Nullable Throwable cause) {
		if (cause == null) {
			return;
		}

		// Do not log warning for serializer not found (note: different message wording on Jackson 2.9)
		boolean debugLevel = (cause instanceof JsonMappingException && cause.getMessage() != null &&
				cause.getMessage().startsWith("Cannot find"));

		if (debugLevel ? logger.isDebugEnabled() : logger.isWarnEnabled()) {
			String msg = "Failed to evaluate Jackson " + (type instanceof JavaType ? "de" : "") +
					"serialization for type [" + type + "]";
			if (debugLevel) {
				logger.debug(msg, cause);
			}
			else if (logger.isDebugEnabled()) {
				logger.warn(msg, cause);
			}
			else {
				logger.warn(msg + ": " + cause);
			}
		}
	}

	@Override
	public Object read(Type type, @Nullable Class<?> contextClass, HttpInputMessage inputMessage)
			throws IOException, HttpMessageNotReadableException {

		JavaType javaType = getJavaType(type, contextClass);
		return readJavaType(javaType, inputMessage);
	}

	@Override
	protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage)
			throws IOException, HttpMessageNotReadableException {

		// 1. 构建 Jackson 的类型描述 (JavaType)
		JavaType javaType = getJavaType(clazz, null);
		// 2. 调用核心读取方法
		return readJavaType(javaType, inputMessage);
	}

	private Object readJavaType(JavaType javaType, HttpInputMessage inputMessage) throws IOException {
		MediaType contentType = inputMessage.getHeaders().getContentType();
		Charset charset = getCharset(contentType);

		ObjectMapper objectMapper = selectObjectMapper(javaType.getRawClass(), contentType);
		Assert.state(objectMapper != null, () -> "No ObjectMapper for " + javaType);

		boolean isUnicode = ENCODINGS.containsKey(charset.name()) ||
				"UTF-16".equals(charset.name()) ||
				"UTF-32".equals(charset.name());
		try {
			// 1. 准备输入流 (Input Stream)
			// 获取 HTTP 请求的原始 InputStream。
			// StreamUtils.nonClosing() 是一个保护措施，防止 Jackson 在读取完毕后自动关闭流。
			// 因为 Servlet 容器通常希望自己管理流的生命周期，而不是由转换器关闭。
			InputStream inputStream = StreamUtils.nonClosing(inputMessage.getBody());

			// 2. 处理 @JsonView 场景 (反序列化视图)
			// 如果当前的 InputMessage 是 MappingJacksonInputMessage 类型，说明它携带了 View 信息。
			// @JsonView 允许你控制只有特定分组的字段才会被反序列化。
			if (inputMessage instanceof MappingJacksonInputMessage mappingJacksonInputMessage) {
				// 获取指定的视图类 (例如: interface User.Basic {})
				Class<?> deserializationView = mappingJacksonInputMessage.getDeserializationView();

				if (deserializationView != null) {
					// 创建一个带有 View 配置的 ObjectReader
					// objectMapper.readerWithView(...) 告诉 Jackson 只处理该 View 包含的字段
					// .forType(javaType) 指定目标对象的类型 (例如 User.class)
					ObjectReader objectReader = objectMapper.readerWithView(deserializationView).forType(javaType);

					// 允许子类进一步自定义 Reader (扩展点)
					objectReader = customizeReader(objectReader, javaType);

					// 执行读取
					if (isUnicode) {
						// 场景 A: 编码是 Unicode (UTF-8/16/32)
						// Jackson 原生对 Unicode 支持极好，直接传入 InputStream 效率最高，
						// Jackson 内部会自动检测 BOM 和具体编码。
						return objectReader.readValue(inputStream);
					}
					else {
						// 场景 B: 编码是非 Unicode (如 ISO-8859-1)
						// 需要手动包装成 InputStreamReader 并指定 Charset，将字节流转换为字符流
						Reader reader = new InputStreamReader(inputStream, charset);
						return objectReader.readValue(reader);
					}
				}
			}

			// 3. 处理标准场景 (无 @JsonView)
			// 创建一个标准的 ObjectReader，用于将 JSON 转换为指定的 javaType
			ObjectReader objectReader = objectMapper.reader().forType(javaType);

			// 允许子类进一步自定义 Reader
			objectReader = customizeReader(objectReader, javaType);

			// 4. 执行读取 (核心反序列化)
			if (isUnicode) {
				// 优化路径：如果是 UTF-8 等 Unicode 编码，直接给 Jackson 字节流
				// 这是目前绝大多数 Web 请求走的路径 (application/json 默认就是 UTF-8)
				return objectReader.readValue(inputStream);
			}
			else {
				// 兼容路径：非 Unicode 编码，手动转字符流再给 Jackson
				Reader reader = new InputStreamReader(inputStream, charset);
				return objectReader.readValue(reader);
			}
		}
		catch (InvalidDefinitionException ex) {
			throw new HttpMessageConversionException("Type definition error: " + ex.getType(), ex);
		}
		catch (JsonProcessingException ex) {
			throw new HttpMessageNotReadableException("JSON parse error: " + ex.getOriginalMessage(), ex, inputMessage);
		}
	}

	/**
	 * Subclasses can use this method to customize {@link ObjectReader} used
	 * for reading values.
	 * @param reader the reader instance to customize
	 * @param javaType the target type of element values to read to
	 * @return the customized {@link ObjectReader}
	 * @since 6.0
	 */
	protected ObjectReader customizeReader(ObjectReader reader, JavaType javaType) {
		return reader;
	}

	/**
	 * Determine the charset to use for JSON input.
	 * <p>By default this is either the charset from the input {@code MediaType}
	 * or otherwise falling back on {@code UTF-8}. Can be overridden in subclasses.
	 * @param contentType the content type of the HTTP input message
	 * @return the charset to use
	 * @since 5.1.18
	 */
	protected Charset getCharset(@Nullable MediaType contentType) {
		if (contentType != null && contentType.getCharset() != null) {
			return contentType.getCharset();
		}
		else {
			return StandardCharsets.UTF_8;
		}
	}

	@Override
	protected void writeInternal(Object object, @Nullable Type type, HttpOutputMessage outputMessage)
			throws IOException, HttpMessageNotWritableException {

		// 1. 确定编码格式
		// 从响应头中获取 Content-Type (如 application/json)，并确定字符集编码 (默认 UTF-8)。
		MediaType contentType = outputMessage.getHeaders().getContentType();
		JsonEncoding encoding = getJsonEncoding(contentType);

		// 2. 确定要使用的 ObjectMapper
		// 如果对象被 MappingJacksonValue 包装（用于动态设置 View/Filter），则获取内部真实的 value 类型。
		// selectObjectMapper 允许根据类或 MediaType 选择不同的 Mapper (通常应用只有一个 Mapper)。
		Class<?> clazz = (object instanceof MappingJacksonValue mappingJacksonValue ?
				mappingJacksonValue.getValue().getClass() : object.getClass());
		ObjectMapper objectMapper = selectObjectMapper(clazz, contentType);
		Assert.state(objectMapper != null, () -> "No ObjectMapper for " + clazz.getName());

		// 3. 准备输出流
		// 使用 StreamUtils.nonClosing 包装输出流，防止 Jackson 在写入完成后自动关闭流。
		// 流的关闭应该由 Servlet 容器（如 Tomcat）负责，而不是由 Converter 负责。
		OutputStream outputStream = StreamUtils.nonClosing(outputMessage.getBody());

		// 创建 Jackson 的底层生成器 JsonGenerator，指定编码
		try (JsonGenerator generator = objectMapper.getFactory().createGenerator(outputStream, encoding)) {
			// 写入前缀（主要用于 JSONP 场景，写入 callback 函数名，现代开发中很少使用）
			writePrefix(generator, object);

			// 4. 准备序列化所需的元数据
			Object value = object;
			Class<?> serializationView = null;
			FilterProvider filters = null;
			JavaType javaType = null;

			// 处理 MappingJacksonValue 包装器
			// 这种包装器通常用于在 Controller 中动态指定本次响应使用的 @JsonView 或 Filter
			if (object instanceof MappingJacksonValue mappingJacksonValue) {
				value = mappingJacksonValue.getValue();
				serializationView = mappingJacksonValue.getSerializationView();
				filters = mappingJacksonValue.getFilters();
			}

			// 处理泛型类型 (Generic Type)
			// 如果 Controller 方法定义了泛型返回类型 (如 List<User>)，这里将其转换为 Jackson 的 JavaType。
			// 这对于正确序列化集合内部的泛型对象至关重要。
			if (type != null && TypeUtils.isAssignable(type, value.getClass())) {
				javaType = getJavaType(type, null);
			}

			// 5. 构建 ObjectWriter (实际执行序列化的对象)
			// 如果存在 @JsonView 配置，创建带有视图支持的 Writer；否则创建默认 Writer。
			ObjectWriter objectWriter = (serializationView != null ?
					objectMapper.writerWithView(serializationView) : objectMapper.writer());

			// 应用动态过滤器 (如果有)
			if (filters != null) {
				objectWriter = objectWriter.with(filters);
			}

			// 绑定泛型类型信息
			// 对于容器类型 (List, Map) 或 Optional，显式指定泛型类型，
			// 防止类型擦除导致 Jackson 将其识别为 LinkedHashMap 而不是具体的 POJO。
			if (javaType != null && (javaType.isContainerType() || javaType.isTypeOrSubTypeOf(Optional.class))) {
				objectWriter = objectWriter.forType(javaType);
			}

			// 6. SSE (Server-Sent Events) 特殊处理
			// 如果是文本事件流且开启了缩进输出，使用特定的 PrettyPrinter，
			// 以避免换行符破坏 SSE 协议的格式。
			SerializationConfig config = objectWriter.getConfig();
			if (contentType != null && contentType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM) &&
					config.isEnabled(SerializationFeature.INDENT_OUTPUT)) {
				objectWriter = objectWriter.with(this.ssePrettyPrinter);
			}

			// 允许子类最后一次自定义 Writer
			objectWriter = customizeWriter(objectWriter, javaType, contentType);

			// 7. 【核心执行】将 Java 对象序列化为 JSON 并写入 Generator
			objectWriter.writeValue(generator, value);

			// 写入后缀 (配合 JSONP 前缀)
			writeSuffix(generator, object);

			// 刷新缓冲区，确保数据写入网络流
			generator.flush();
		}
		catch (InvalidDefinitionException ex) {
			// 处理类型定义错误 (如缺少序列化器、无限递归等)
			throw new HttpMessageConversionException("Type definition error: " + ex.getType(), ex);
		}
		catch (JsonProcessingException ex) {
			// 处理一般的 JSON 处理错误，包装为 Spring 的异常
			throw new HttpMessageNotWritableException("Could not write JSON: " + ex.getOriginalMessage(), ex);
		}
	}

	/**
	 * Subclasses can use this method to customize {@link ObjectWriter} used
	 * for writing values.
	 * @param writer the writer instance to customize
	 * @param javaType the type of element values to write
	 * @param contentType the selected media type
	 * @return the customized {@link ObjectWriter}
	 * @since 6.0
	 */
	protected ObjectWriter customizeWriter(
			ObjectWriter writer, @Nullable JavaType javaType, @Nullable MediaType contentType) {

		return writer;
	}

	/**
	 * Write a prefix before the main content.
	 * @param generator the generator to use for writing content.
	 * @param object the object to write to the output message.
	 */
	protected void writePrefix(JsonGenerator generator, Object object) throws IOException {
	}

	/**
	 * Write a suffix after the main content.
	 * @param generator the generator to use for writing content.
	 * @param object the object to write to the output message.
	 */
	protected void writeSuffix(JsonGenerator generator, Object object) throws IOException {
	}

	/**
	 * Return the Jackson {@link JavaType} for the specified type and context class.
	 * @param type the generic type to return the Jackson JavaType for
	 * @param contextClass a context class for the target type, for example a class
	 * in which the target type appears in a method signature (can be {@code null})
	 * @return the Jackson JavaType
	 */
	protected JavaType getJavaType(Type type, @Nullable Class<?> contextClass) {
		return this.defaultObjectMapper.constructType(GenericTypeResolver.resolveType(type, contextClass));
	}

	/**
	 * Determine the JSON encoding to use for the given content type.
	 * @param contentType the media type as requested by the caller
	 * @return the JSON encoding to use (never {@code null})
	 */
	protected JsonEncoding getJsonEncoding(@Nullable MediaType contentType) {
		if (contentType != null && contentType.getCharset() != null) {
			Charset charset = contentType.getCharset();
			JsonEncoding encoding = ENCODINGS.get(charset.name());
			if (encoding != null) {
				return encoding;
			}
		}
		return JsonEncoding.UTF8;
	}

	@Override
	protected @Nullable MediaType getDefaultContentType(Object object) throws IOException {
		if (object instanceof MappingJacksonValue mappingJacksonValue) {
			object = mappingJacksonValue.getValue();
		}
		return super.getDefaultContentType(object);
	}

	@Override
	protected @Nullable Long getContentLength(Object object, @Nullable MediaType contentType) throws IOException {
		if (object instanceof MappingJacksonValue mappingJacksonValue) {
			object = mappingJacksonValue.getValue();
		}
		return super.getContentLength(object, contentType);
	}

	@Override
	protected boolean supportsRepeatableWrites(Object o) {
		return true;
	}
}
