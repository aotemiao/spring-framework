# Spring 启动路线图

This document outlines the execution order of the Spring Framework startup process, based on `AbstractApplicationContext.refresh()`. It maps user-added comments to the corresponding steps.

## 0. 容器初始化 (Container Initialization)
**Location:** `spring-context/AnnotationConfigApplicationContext.java`
- [Line 89](spring-context/src/main/java/org/springframework/context/annotation/AnnotationConfigApplicationContext.java#89): `// 构造函数：传入配置类`
- [Line 69](spring-context/src/main/java/org/springframework/context/annotation/AnnotationConfigApplicationContext.java#69): `// 1. 初始化 BeanDefinitionReader (注册内置处理器)`

### 注册内置处理器 (Register Processors)
**Location:** `spring-context/AnnotatedBeanDefinitionReader.java`
- [Line 90](spring-context/src/main/java/org/springframework/context/annotation/AnnotatedBeanDefinitionReader.java#90): `// 注册 AnnotationConfigProcessors (包括 ConfigurationClassPostProcessor)`

### 注册配置类 (Register Config Class)
**Location:** `spring-context/AnnotationConfigApplicationContext.java`
- [Line 167](spring-context/src/main/java/org/springframework/context/annotation/AnnotationConfigApplicationContext.java#167): `// 2. 注册传入的配置类 (AppConfig)`

## 1. 准备刷新 (Prepare Refresh)
**Location:** `spring-context/AbstractApplicationContext.java`
- [Line 587](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#587): `// 1. 准备阶段：记录启动时间，设置启动标志等`

## 2. 获取 BeanFactory (Obtain Fresh BeanFactory)
- [Line 591](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#591): `// 2.【关键步骤】获取 BeanFactory：加载XML文件，解析成 BeanDefinition，并创建 BeanFactory`

## 3. 准备 BeanFactory (Prepare BeanFactory)
- [Line 595](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#595): `// 3. 准备 BeanFactory：配置 BeanFactory，比如设置类加载器、添加一些默认的 BeanPostProcessor`

## 4. BeanFactory 后置处理 (Post Process BeanFactory)
- [Line 600](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#600): `// 4. BeanFactory 的后置处理：允许子类对 BeanFactory 进行扩展处理`

## 5. 调用 BeanFactoryPostProcessor
- [Line 605](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#605): `// 5.【关键步骤】执行 BeanFactoryPostProcessor：在所有 BeanDefinition 加载完成，但 Bean 实例还未创建时，`
- [Line 606](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#606): `// 对 BeanDefinition 进行修改或增强。`

### ConfigurationClassPostProcessor (配置类后置处理器)
**Location:** `spring-context/ConfigurationClassPostProcessor.java`
- [Line 327](spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassPostProcessor.java#327): `// 增强 @Configuration 类`
- [Line 329](spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassPostProcessor.java#329): `// 注册 ImportAwareBeanPostProcessor，以便支持 @Import 以及 ImportAware 的语义`

## 6. 注册 BeanPostProcessor
**Location:** `AbstractApplicationContext.java`
- [Line 610](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#610): `// 6.【关键步骤】注册 BeanPostProcessor：将 BeanPostProcessor 注册到 BeanFactory 中，`
- [Line 611](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#611): `// 它们将在 Bean 实例化过程中被调用。`

## 7. 初始化 MessageSource
- [Line 616](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#616): `// 7. 初始化 MessageSource (国际化相关)`

## 8. 初始化事件广播器
- [Line 620](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#620): `// 8. 初始化事件广播器`

## 9. 刷新回调 (On Refresh)
- [Line 624](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#624): `// 9. onRefresh()：留给子类实现的扩展点`

## 10. 注册监听器
- [Line 628](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#628): `// 10. 注册监听器`

## 11. 完成 BeanFactory 初始化
- [Line 632](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#632): `// 11.【关键步骤】完成 BeanFactory 的初始化：实例化所有剩余的非懒加载的单例 Bean`
- [Line 994](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#994): `// 创建所有非懒加载的单例 Bean 实例`

### Bean 实例化 (doGetBean)
**Location:** `spring-beans/AbstractBeanFactory.java`
- [Line 244](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java#244): `// 检查单例缓存`
- [Line 256](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java#256): `// 缓存中获取到的 sharedInstance 可能是一个普通的 Bean，也可能是一个 FactoryBean。`
- [Line 266](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java#266): `// 检查原型 Bean 的循环依赖`
- [Line 274](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java#274): `// 如果当前容器中没有这个 Bean 的定义，尝试从父容器中获取`
- [Line 297](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java#297): `// 将当前 Bean 标记为“已创建”（实际上是“开始创建”），用于循环依赖检查`
- [Line 312](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java#312): `// 处理 depends-on 依赖`
- [Line 348](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java#348): `// 根据不同的 scope 创建 Bean 实例`
- [Line 350](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java#350): `// 单例 (Singleton)`
- [Line 363](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java#363): `// 如果创建失败，需要清理缓存，因为在解决循环依赖时可能已经提前放入了不完整的 Bean`
- [Line 373](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java#373): `// 原型 (Prototype)`

### 单例注册表 (循环依赖)
**Location:** `spring-beans/DefaultSingletonBeanRegistry.java`
- [Line 210](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java#210): `// 1. 快速路径检查：首先检查一级缓存 (singletonObjects)`
- [Line 213](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java#213): `// 2. 如果一级缓存中没有，并且当前 Bean 正在创建中（这是循环依赖的典型特征）`
- [Line 215](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java#215): `// 3. 接着检查二级缓存 (earlySingletonObjects)`
- [Line 218](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java#218): `// 4. 如果二级缓存中也没有，并且允许早期引用`
- [Line 227](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java#227): `// 5. 【双重检查锁定】进入同步代码块后，再次检查一、二级缓存。`
- [Line 233](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java#233): `// 6. 如果一、二级缓存都没有，尝试从三级缓存 (singletonFactories) 中获取 ObjectFactory。`
- [Line 237](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java#237): `// 7. 【核心】如果工厂存在，则调用 getObject() 方法创建早期引用。`
- [Line 241](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java#241): `// 8. 【升级】将创建出的早期引用从三级缓存移动到二级缓存。`
- [Line 257](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java#257): `// 9. 释放锁。`
- [Line 262](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java#262): `// 10. 返回找到的 Bean 实例`

### Bean 创建详情 (createBean & doCreateBean)
**Location:** `spring-beans/AbstractAutowireCapableBeanFactory.java`
- [Line 500](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java#500): `// 1. 解析 Bean 的 Class 类型`
- [Line 523](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java#523): `// 2. 【重要】实例化前的后置处理（AOP 关键切入点）`
- [Line 538](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java#538): `// 3. 核心创建逻辑的委派`
- [Line 576](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java#576): `// 1. 【实例化】`
- [Line 613](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java#613): `// 2. 【解决循环依赖的关键】提前曝光单例`
- [Line 629](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java#629): `// 3. 【初始化 Bean】`
- [Line 633](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java#633): `// 3.1 【属性填充】`
- [Line 637](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java#637): `// 3.2 【初始化】`
- [Line 656](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java#656): `// 4. 【循环依赖的最终检查】`
- [Line 693](spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java#693): `// 5. 【注册销毁逻辑】`

### 预实例化单例
**Location:** `spring-beans/DefaultListableBeanFactory.java`
- [Line 1124](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java#1124): `// 等待所有后台初始化结束`
- [Line 1133](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java#1133): `// 触发 SmartInitializingSingleton 回调`
- [Line 1135](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java#1135): `// 从单例池 getSingleton(beanName, false) 拿到已创建好的实例`
- [Line 1140](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java#1140): `// 在单例预实例化阶段的最后被调用`
- [Line 1157](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java#1157): `// 使用提供的线程池 executor 异步执行 bean 的实例化逻辑`
- [Line 1161](spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java#1161): `// 早期暴露`

### AOP 代理创建
**Location:** `spring-aop/AbstractAutoProxyCreator.java`
- [Line 291](spring-aop/src/main/java/org/springframework/aop/framework/autoproxy/AbstractAutoProxyCreator.java#291): `// 2. --- 核心判断逻辑 ---`
- [Line 364](spring-aop/src/main/java/org/springframework/aop/framework/autoproxy/AbstractAutoProxyCreator.java#364): `// --- 第一部分：快速失败与跳过检查 ---`
- [Line 383](spring-aop/src/main/java/org/springframework/aop/framework/autoproxy/AbstractAutoProxyCreator.java#383): `// --- 第二部分：核心代理创建逻辑 ---`
- [Line 386](spring-aop/src/main/java/org/springframework/aop/framework/autoproxy/AbstractAutoProxyCreator.java#386): `// 1. 【核心】为当前 Bean 查找匹配的通知（Advices）和顾问（Advisors）`
- [Line 395](spring-aop/src/main/java/org/springframework/aop/framework/autoproxy/AbstractAutoProxyCreator.java#395): `// 3. 【执行】创建代理对象`
- [Line 508](spring-aop/src/main/java/org/springframework/aop/framework/autoproxy/AbstractAutoProxyCreator.java#508): `// --- 准备阶段：配置装配工具 (ProxyFactory) ---`
- [Line 523](spring-aop/src/main/java/org/springframework/aop/framework/autoproxy/AbstractAutoProxyCreator.java#523): `// --- 决策阶段：选择代理策略（用 CGLIB 还是 JDK 动态代理？）---`
- [Line 563](spring-aop/src/main/java/org/springframework/aop/framework/autoproxy/AbstractAutoProxyCreator.java#563): `// --- 组装阶段：将 AOP 组件装配到工厂 ---`
- [Line 575](spring-aop/src/main/java/org/springframework/aop/framework/autoproxy/AbstractAutoProxyCreator.java#575): `// --- 生产阶段：生成最终的代理对象 ---`

### AOP 代理执行 (运行时)
**Location:** `spring-aop/JdkDynamicAopProxy.java`
- [Line 174](spring-aop/src/main/java/org/springframework/aop/framework/JdkDynamicAopProxy.java#174): `// --- 第一部分：特殊方法的快速通道处理 ---`
- [Line 202](spring-aop/src/main/java/org/springframework/aop/framework/JdkDynamicAopProxy.java#202): `// --- 第二部分：准备工作与获取“调用链” ---`
- [Line 220](spring-aop/src/main/java/org/springframework/aop/framework/JdkDynamicAopProxy.java#220): `// 7. 【核心步骤】获取将要应用于此方法的“拦截器链”。`
- [Line 226](spring-aop/src/main/java/org/springframework/aop/framework/JdkDynamicAopProxy.java#226): `// --- 第三部分：执行调用链与目标方法 ---`
- [Line 230](spring-aop/src/main/java/org/springframework/aop/framework/JdkDynamicAopProxy.java#230): `// 8. 如果拦截器链为空，说明没有任何通知需要应用到此方法上。`
- [Line 241](spring-aop/src/main/java/org/springframework/aop/framework/JdkDynamicAopProxy.java#241): `// 9. 【核心步骤】如果拦截器链不为空，就需要执行 AOP 逻辑。`
- [Line 246](spring-aop/src/main/java/org/springframework/aop/framework/JdkDynamicAopProxy.java#246): `//    【启动调用链】调用 proceed() 方法，这会像多米诺骨牌一样，`
- [Line 252](spring-aop/src/main/java/org/springframework/aop/framework/JdkDynamicAopProxy.java#252): `// --- 第四部分：返回值处理 ---`
- [Line 255](spring-aop/src/main/java/org/springframework/aop/framework/JdkDynamicAopProxy.java#255): `// 10. 对返回值进行一些特殊处理。`
- [Line 280](spring-aop/src/main/java/org/springframework/aop/framework/JdkDynamicAopProxy.java#280): `// --- 第五部分：清理工作 ---`

**Location:** `spring-aop/ReflectiveMethodInvocation.java`
- [Line 157](spring-aop/src/main/java/org/springframework/aop/framework/ReflectiveMethodInvocation.java#157): `// 1. 判断是否已执行完所有拦截器 (递归的终止条件)`
- [Line 166](spring-aop/src/main/java/org/springframework/aop/framework/ReflectiveMethodInvocation.java#166): `// 2. 获取下一个要执行的拦截器`
- [Line 172](spring-aop/src/main/java/org/springframework/aop/framework/ReflectiveMethodInvocation.java#172): `// 3. 判断拦截器的类型`
- [Line 176](spring-aop/src/main/java/org/springframework/aop/framework/ReflectiveMethodInvocation.java#176): `// 3.1 类型一：动态匹配的拦截器`
- [Line 199](spring-aop/src/main/java/org/springframework/aop/framework/ReflectiveMethodInvocation.java#199): `// 3.2 类型二：静态匹配的拦截器`

## 12. 完成刷新
**Location:** `AbstractApplicationContext.java`
- [Line 636](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#636): `// 12. 完成刷新过程：发布容器刷新完成事件`

### 清理与生命周期
- [Line 1005](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#1005): `// 1. 清理临时缓存`
- [Line 1016](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#1016): `// 2. 初始化生命周期处理器`
- [Line 1022](spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#1022): `// 3. 触发 Lifecycle Bean 的启动`

## 13. Spring 声明式事务 (Declarative Transaction Management)

### 开启事务管理 (Enable Transaction Management)
**Location:** `spring-tx/EnableTransactionManagement.java`
- [Line 162](spring-tx/src/main/java/org/springframework/transaction/annotation/EnableTransactionManagement.java#162): `// @Import(TransactionManagementConfigurationSelector.class) 导入配置选择器`

**Location:** `spring-tx/TransactionManagementConfigurationSelector.java`
- [Line 49](spring-tx/src/main/java/org/springframework/transaction/annotation/TransactionManagementConfigurationSelector.java#49): `// 默认导入 ProxyTransactionManagementConfiguration (PROXY 模式)`

**Location:** `spring-tx/ProxyTransactionManagementConfiguration.java`
- [Line 60](spring-tx/src/main/java/org/springframework/transaction/annotation/ProxyTransactionManagementConfiguration.java#60): `// 注册 TransactionInterceptor (事务拦截器)`
- [Line 46](spring-tx/src/main/java/org/springframework/transaction/annotation/ProxyTransactionManagementConfiguration.java#46): `// 注册 BeanFactoryTransactionAttributeSourceAdvisor (AOP 切面)`

> [!NOTE]
> **Spring Data vs Spring Boot**: 引入 `spring-data` 依赖并不会自动开启事务管理。在非 Spring Boot 应用中，必须显式添加 `@EnableTransactionManagement`。Spring Boot 是通过 `TransactionAutoConfiguration` 自动添加了这个注解。

### 事务拦截器 (Transaction Interceptor)
**Location:** `spring-tx/TransactionInterceptor.java`
- [Line 118](spring-tx/src/main/java/org/springframework/transaction/interceptor/TransactionInterceptor.java#118): `// invoke() 方法委托给父类的 invokeWithinTransaction`

**Location:** `spring-tx/TransactionAspectSupport.java`
- [Line 332](spring-tx/src/main/java/org/springframework/transaction/interceptor/TransactionAspectSupport.java#332): `// 【核心】事务处理的主流程 (invokeWithinTransaction)`
- [Line 364](spring-tx/src/main/java/org/springframework/transaction/interceptor/TransactionAspectSupport.java#364): `// 1. 开启事务 (createTransactionIfNecessary)`
- [Line 370](spring-tx/src/main/java/org/springframework/transaction/interceptor/TransactionAspectSupport.java#370): `// 2. 执行目标方法 (invocation.proceedWithInvocation())`
- [Line 374](spring-tx/src/main/java/org/springframework/transaction/interceptor/TransactionAspectSupport.java#374): `// 3. 异常回滚 (completeTransactionAfterThrowing)`
- [Line 406](spring-tx/src/main/java/org/springframework/transaction/interceptor/TransactionAspectSupport.java#406): `// 4. 成功提交 (commitTransactionAfterReturning)`

### 事务管理器实现 (DataSourceTransactionManager)
**Location:** `spring-jdbc/DataSourceTransactionManager.java`
- [Line 263](spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceTransactionManager.java#263): `// doBegin: 开启事务的具体实现`
- [Line 270](spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceTransactionManager.java#270): `// 1. 获取数据库连接 (obtainDataSource().getConnection())`
- [Line 297](spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceTransactionManager.java#297): `// 2. 【核心】关闭自动提交 (con.setAutoCommit(false))`
- [Line 310](spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceTransactionManager.java#310): `// 3. 绑定连接到当前线程 (TransactionSynchronizationManager.bindResource)`
- [Line 336](spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceTransactionManager.java#336): `// doCommit: 提交事务 (con.commit())`
- [Line 351](spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceTransactionManager.java#351): `// doRollback: 回滚事务 (con.rollback())`

## 14. Spring MVC 请求处理 (Request Processing)

### 核心处理流程 (RequestResponseBodyMethodProcessor)
**Location:** `spring-webmvc/RequestResponseBodyMethodProcessor.java`

**参数解析 (Argument Resolution):**
- [Line 130](spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestResponseBodyMethodProcessor.java#130): `// 解析加了 @RequestBody 注解的参数`
- [Line 152](spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestResponseBodyMethodProcessor.java#152): `// 1. 处理 Optional 包装`
- [Line 158](spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestResponseBodyMethodProcessor.java#158): `// 2. 【核心】读取 HTTP Body 并反序列化`
- [Line 163](spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestResponseBodyMethodProcessor.java#163): `// 3. 数据绑定与校验流程`
- [Line 175](spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestResponseBodyMethodProcessor.java#175): `// 4. 执行校验逻辑`
- [Line 180](spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestResponseBodyMethodProcessor.java#180): `// 5. 决定是否抛出异常`
- [Line 191](spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestResponseBodyMethodProcessor.java#191): `// 6. 保存校验结果`
- [Line 199](spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestResponseBodyMethodProcessor.java#199): `// 7. 适配返回值`

**返回值处理 (Return Value Handling):**
- [Line 136](spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestResponseBodyMethodProcessor.java#136): `// 1. 检查方法上有没有 @ResponseBody`
- [Line 229](spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestResponseBodyMethodProcessor.java#229): `// 1. 【关键】标记请求已处理 (Flag as Handled)`
- [Line 235](spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestResponseBodyMethodProcessor.java#235): `// 2. 包装 Request 和 Response`
- [Line 241](spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestResponseBodyMethodProcessor.java#241): `// 3. 【Spring 6 新特性】处理 ProblemDetail (RFC 7807 错误详情)`
- [Line 260](spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestResponseBodyMethodProcessor.java#260): `// 4. 【核心】调用消息转换器进行写入`

### JSON 消息转换 (AbstractJackson2HttpMessageConverter)
**Location:** `spring-web/AbstractJackson2HttpMessageConverter.java`

**读取 JSON (Read):**
- [Line 364](spring-web/src/main/java/org/springframework/http/converter/json/AbstractJackson2HttpMessageConverter.java#364): `// 1. 构建 Jackson 的类型描述 (JavaType)`
- [Line 381](spring-web/src/main/java/org/springframework/http/converter/json/AbstractJackson2HttpMessageConverter.java#381): `// 1. 准备输入流 (Input Stream)`
- [Line 387](spring-web/src/main/java/org/springframework/http/converter/json/AbstractJackson2HttpMessageConverter.java#387): `// 2. 处理 @JsonView 场景 (反序列化视图)`
- [Line 419](spring-web/src/main/java/org/springframework/http/converter/json/AbstractJackson2HttpMessageConverter.java#419): `// 3. 处理标准场景 (无 @JsonView)`
- [Line 426](spring-web/src/main/java/org/springframework/http/converter/json/AbstractJackson2HttpMessageConverter.java#426): `// 4. 执行读取 (核心反序列化)`

**写入 JSON (Write):**
- [Line 479](spring-web/src/main/java/org/springframework/http/converter/json/AbstractJackson2HttpMessageConverter.java#479): `// 1. 确定编码格式`
- [Line 484](spring-web/src/main/java/org/springframework/http/converter/json/AbstractJackson2HttpMessageConverter.java#484): `// 2. 确定要使用的 ObjectMapper`
- [Line 492](spring-web/src/main/java/org/springframework/http/converter/json/AbstractJackson2HttpMessageConverter.java#492): `// 3. 准备输出流`
- [Line 502](spring-web/src/main/java/org/springframework/http/converter/json/AbstractJackson2HttpMessageConverter.java#502): `// 4. 准备序列化所需的元数据`
- [Line 523](spring-web/src/main/java/org/springframework/http/converter/json/AbstractJackson2HttpMessageConverter.java#523): `// 5. 构建 ObjectWriter (实际执行序列化的对象)`
- [Line 540](spring-web/src/main/java/org/springframework/http/converter/json/AbstractJackson2HttpMessageConverter.java#540): `// 6. SSE (Server-Sent Events) 特殊处理`
- [Line 552](spring-web/src/main/java/org/springframework/http/converter/json/AbstractJackson2HttpMessageConverter.java#552): `// 7. 【核心执行】将 Java 对象序列化为 JSON 并写入 Generator`

### Spring MVC 工作流梗概 (Workflow Summary)

1.  **DispatcherServlet**: 接收 HTTP 请求，作为前端控制器。
2.  **HandlerMapping**: 根据 URL 找到对应的 Handler (Controller 方法)。
3.  **HandlerAdapter**: 负责调用 Handler。对于 `@RequestMapping` 方法，通常使用 `RequestMappingHandlerAdapter`。
4.  **ArgumentResolver**: 在调用方法前，解析参数。
    - 如果参数带 `@RequestBody`，`RequestResponseBodyMethodProcessor` 会介入。
    - 它调用 `HttpMessageConverter` (如 Jackson) 读取 Body 并转为 Java 对象。
    - 随后进行数据绑定 (WebDataBinder) 和校验 (Validator)。
5.  **Method Invocation**: 执行 Controller 方法逻辑。
6.  **ReturnValueHandler**: 处理返回值。
    - 如果方法带 `@ResponseBody`，`RequestResponseBodyMethodProcessor` 再次介入。
    - 它设置 `requestHandled=true`，防止视图解析。
    - 它调用 `HttpMessageConverter` 将返回值序列化为 JSON 等格式写入响应流。

## 15. Spring MVC 配置 (Configuration)

### 开启 MVC 配置 (@EnableWebMvc)
**Location:** `spring-webmvc/EnableWebMvc.java`
- [Line 100](spring-webmvc/src/main/java/org/springframework/web/servlet/config/annotation/EnableWebMvc.java#100): `// @Import(DelegatingWebMvcConfiguration.class) 导入配置类`

### 委派配置 (DelegatingWebMvcConfiguration)
**Location:** `spring-webmvc/DelegatingWebMvcConfiguration.java`
- [Line 46](spring-webmvc/src/main/java/org/springframework/web/servlet/config/annotation/DelegatingWebMvcConfiguration.java#46): `// 继承 WebMvcConfigurationSupport，是 @EnableWebMvc 实际导入的类`
- [Line 51](spring-webmvc/src/main/java/org/springframework/web/servlet/config/annotation/DelegatingWebMvcConfiguration.java#51): `// 1. 【收集】注入所有 WebMvcConfigurer Bean`
- [Line 96](spring-webmvc/src/main/java/org/springframework/web/servlet/config/annotation/DelegatingWebMvcConfiguration.java#96): `// 2. 【委派】将配置回调委派给 configurers (如 addInterceptors)`

### 核心配置支持 (WebMvcConfigurationSupport)
**Location:** `spring-webmvc/WebMvcConfigurationSupport.java`
- [Line 267](spring-webmvc/src/main/java/org/springframework/web/servlet/config/annotation/WebMvcConfigurationSupport.java#267): `// 注册 RequestMappingHandlerMapping (处理 @RequestMapping)`
- [Line 514](spring-webmvc/src/main/java/org/springframework/web/servlet/config/annotation/WebMvcConfigurationSupport.java#514): `// 【关键】注入拦截器 (调用 getInterceptors -> addInterceptors)`
- [Line 650](spring-webmvc/src/main/java/org/springframework/web/servlet/config/annotation/WebMvcConfigurationSupport.java#650): `// 注册 RequestMappingHandlerAdapter (调用 Controller 方法)`
