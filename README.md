# grpc客户端

rpc模块分为客户端和服务端，服务端向注册发现中心提供元数据(host，port等数据)，客户端通过注册发现中心得到服务元数据，随后通过元数据发起tcp请求。

客户端模块在sptingboot项目中的功能和spring-boot-web-starter相似，集成了spring-boot-web-starter就可以通过创建controller实现http服务，所以集成了grpc-client-starter模块也可以相应的实现grpc服务请求的发起。

## GrpcClientAutoConfiguration
GrpcClientAutoConfiguration 模块初始化模块，在普通项目中通过注解扫描的形式被spring加载，作为一个starter模块，需要在`META-INF`的`spring.factories`中配置

```java
org.springframework.boot.autoconfigure.EnableAutoConfiguration=
com.ecreditpal.cloud.grpc.client.starter.GrpcClientAutoConfiguration
```

```
@AutoConfigureAfter(CompositeDiscoveryClientAutoConfiguration.class)
@EnableConfigurationProperties
@ConditionalOnClass({GrpcChannelFactory.class})
@ConditionalOnBean(DiscoveryClient.class)
public class GrpcClientAutoConfiguration {

    @ConditionalOnMissingBean
    @Bean
    public GrpcChannelsProperties grpcChannelsProperties() {
        return new GrpcChannelsProperties();
    }

    @Bean
    public GlobalClientInterceptorRegistry globalClientInterceptorRegistry() {
        return new GlobalClientInterceptorRegistry();
    }

    @ConditionalOnMissingBean
    @Bean
    public LoadBalancer.Factory grpcLoadBalancerFactory() {
        return RoundRobinLoadBalancerFactory.getInstance();
    }


    @Bean
    @ConditionalOnClass({GrpcChannelFactory.class,GrpcClient.class})
    public GrpcClientBeanPostProcessor grpcClientBeanPostProcessor() {
        return new GrpcClientBeanPostProcessor();
    }



    @Configuration
    @AutoConfigureAfter(DiscoveryClient.class)
//    @ConditionalOnBean(EurekaDiscoveryClient.class)
    protected static class DiscoveryGrpcClientAutoConfiguration {

        @Bean
        public GrpcChannelFactory discoveryClientChannelFactory(GrpcChannelsProperties channels, DiscoveryClient discoveryClient, LoadBalancer.Factory loadBalancerFactory,GlobalClientInterceptorRegistry globalClientInterceptorRegistry) {
            return new DiscoveryClientChannelFactory(channels, discoveryClient, loadBalancerFactory, globalClientInterceptorRegistry);
        }
    }
}
```

需要初始化的模块有

* GrpcChannelsProperties 为全局配置grpc通道的属性，或者为多个通道进行个性化配置
* GlobalClientInterceptorRegistry 用于加载自定义的拦截器，例如可以用于记录请求日志
* LoadBalancer.Factory 配置负载均衡策略，当存在服务端存在多个grpc端点时，默认选择使用grpc自带的轮询方式请求
* GrpcClientBeanPostProcessor 核心模块，通过注释方式注入连接各个服务端的channel
* GrpcChannelFactory 通过服务发现的形式构造channel


## GrpcChannelsProperties

最初开始是用于直接配置多个channel的元数据信息，包括host和port，可以通过name直接获取配置，并初始化channel，随后发起调动。因为使用了服务注册与发现，所以这部分的核心作用被转移了，默认使用GrpcChannelProperties.DEFAULT的配置


```
@Data
@ConfigurationProperties("grpc")
public class GrpcChannelsProperties {

    @NestedConfigurationProperty
    private Map<String, GrpcChannelProperties> client = Maps.newHashMap();

    public GrpcChannelProperties getChannel(String name) {
        GrpcChannelProperties grpcChannelProperties = client.get(name);
        if (grpcChannelProperties == null) {
            grpcChannelProperties = GrpcChannelProperties.DEFAULT;
        }
        return grpcChannelProperties;
    }
}
```


## GlobalClientInterceptorRegistry

```java
@Getter
public class GlobalClientInterceptorRegistry implements ApplicationContextAware {

    private final List<ClientInterceptor> clientInterceptors = Lists.newArrayList();
    private ApplicationContext applicationContext;

    @PostConstruct
    public void init() {
        Map<String, GlobalClientInterceptorConfigurerAdapter> map = applicationContext.getBeansOfType(GlobalClientInterceptorConfigurerAdapter.class);
        for (GlobalClientInterceptorConfigurerAdapter globalClientInterceptorConfigurerAdapter : map.values()) {
            globalClientInterceptorConfigurerAdapter.addClientInterceptors(this);
        }
    }

    public GlobalClientInterceptorRegistry addClientInterceptors(ClientInterceptor interceptor) {
        clientInterceptors.add(interceptor);
        return this;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
```

可以通过两种方式注册拦截器

第一种是获取`GlobalClientInterceptorRegistry`直接调用`addClientInterceptors`
第二种是实现GlobalClientInterceptorConfigurerAdapter并交给spring来加载

```
@Configuration
public class GlobalClientInterceptorConfiguration {

    @Bean
    public GlobalClientInterceptorConfigurerAdapter globalInterceptorConfigurerAdapter() {
        return new GlobalClientInterceptorConfigurerAdapter() {

            @Override
            public void addClientInterceptors(GlobalClientInterceptorRegistry registry) {
                registry.addClientInterceptors(new LogGrpcInterceptor());
            }
        };
    }
}

public class LogGrpcInterceptor implements ClientInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LogGrpcInterceptor.class);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        log.info(method.getFullMethodName());
        return next.newCall(method, callOptions);
    }
}
```

## GrpcClientBeanPostProcessor

通过实现BeanPostProcessor注册一个bean加载完成后的后置处理器。

在postProcessBeforeInitialization方法中，找到field被@GrpcClient注册的bean，加入map中，@GrpcClient 上声明了服务端的名称，我们可以通过这个名称从服务注册中心获取对应服务的源数据。

在postProcessAfterInitialization方法中，我们通过GrpcChannelFactory构造对应服务的channel，并注入被@GrpcClient注释的field中，之后客户端就可以使用channel发起rpc调用

```java
public class GrpcClientBeanPostProcessor implements org.springframework.beans.factory.config.BeanPostProcessor {

    private Map<String, List<Class>> beansToProcess = Maps.newHashMap();

    @Autowired
    private DefaultListableBeanFactory beanFactory;

    @Autowired
    private GrpcChannelFactory channelFactory;

    public GrpcClientBeanPostProcessor() {
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        Class clazz = bean.getClass();
        do {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(GrpcClient.class)) {
                    if (!beansToProcess.containsKey(beanName)) {
                        beansToProcess.put(beanName, new ArrayList<Class>());
                    }
                    beansToProcess.get(beanName).add(clazz);
                }
            }
            clazz = clazz.getSuperclass();
        } while (clazz != null);
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (beansToProcess.containsKey(beanName)) {
            Object target = getTargetBean(bean);
            for (Class clazz : beansToProcess.get(beanName)) {
                for (Field field : clazz.getDeclaredFields()) {
                    GrpcClient annotation = AnnotationUtils.getAnnotation(field, GrpcClient.class);
                    if (null != annotation) {

                        List<ClientInterceptor> list = Lists.newArrayList();
                        for (Class<? extends ClientInterceptor> clientInterceptorClass : annotation.interceptors()) {
                            ClientInterceptor clientInterceptor;
                            if (beanFactory.getBeanNamesForType(ClientInterceptor.class).length > 0) {
                                clientInterceptor = beanFactory.getBean(clientInterceptorClass);
                            } else {
                                try {
                                    clientInterceptor = clientInterceptorClass.newInstance();
                                } catch (Exception e) {
                                    throw new BeanCreationException("Failed to create interceptor instance", e);
                                }
                            }
                            list.add(clientInterceptor);
                        }

                        Channel channel = channelFactory.createChannel(annotation.value(), list);
                        ReflectionUtils.makeAccessible(field);
                        ReflectionUtils.setField(field, target, channel);
                    }
                }
            }
        }
        return bean;
    }

    @SneakyThrows
    private Object getTargetBean(Object bean) {
        Object target = bean;
        while (AopUtils.isAopProxy(target)) {
            target = ((Advised) target).getTargetSource().getTarget();
        }
        return target;
    }


}
```


## GrpcChannelFactory

最最最核心的部分
功能已经介绍过了，就是创建channel。

该factory的运行的基础分为以下部分

* DiscoveryClient 服务发现者，如果客户端启用了eureka，spring容器中会生成EurekaDiscoverClient,但是GrpcChannelFactory并不直接调用它，而是交给DiscoveryClientNameResolver使用
* DiscoveryClientNameResolver，通过name从eureka中获取服务端的元数据。每个channel都有一个DiscoveryClientNameResolver，并使用线程池执行任务:轮询eureka，获取并更新最新的服务端配置。保证调用能够通畅
* LoadBalancer.Factory 使用Grpc提供的服务调用策略，RoundRobin(轮询)
* GlobalClientInterceptorRegistry 在channel加载的时候加入interceptor配置，这样在使用channel的时候能够触发interceptor的功能




```java
public class DiscoveryClientChannelFactory implements GrpcChannelFactory {
    private final GrpcChannelsProperties properties;
    private final DiscoveryClient client;
    private final LoadBalancer.Factory loadBalancerFactory;
    private final GlobalClientInterceptorRegistry globalClientInterceptorRegistry;
    private HeartbeatMonitor monitor = new HeartbeatMonitor();

    /**
     *
     * @param properties GrpcChannels 通道全局配置
     * @param client    发现服务对象,默认使用eurekaDiscoveryClient
     * @param loadBalancerFactory 负载均衡器, 默认使用RoundRobinLoadBalance
     * @param globalClientInterceptorRegistry 客户端拦截器配置
     */
    public DiscoveryClientChannelFactory(GrpcChannelsProperties properties, DiscoveryClient client, LoadBalancer.Factory loadBalancerFactory,
                                         GlobalClientInterceptorRegistry globalClientInterceptorRegistry) {
        this.properties = properties;
        this.client = client;
        this.loadBalancerFactory = loadBalancerFactory;
        this.globalClientInterceptorRegistry = globalClientInterceptorRegistry;
    }

    private List<DiscoveryClientNameResolver> discoveryClientNameResolvers = Lists.newArrayList();

    public void addDiscoveryClientNameResolver(DiscoveryClientNameResolver discoveryClientNameResolver) {
        discoveryClientNameResolvers.add(discoveryClientNameResolver);
    }

    @EventListener(HeartbeatEvent.class)
    public void heartbeat(HeartbeatEvent event) {
        if (this.monitor.update(event.getValue())) {
            for (DiscoveryClientNameResolver discoveryClientNameResolver : discoveryClientNameResolvers) {
                discoveryClientNameResolver.refresh();
            }
        }
    }

    @Override
    public Channel createChannel(String name) {
        return this.createChannel(name, null);
    }

    @Override
    public Channel createChannel(String name, List<ClientInterceptor> interceptors) {
        GrpcChannelProperties channelProperties = properties.getChannel(name);
        NettyChannelBuilder builder = NettyChannelBuilder.forTarget(name)
                .loadBalancerFactory(loadBalancerFactory)
                .nameResolverFactory(new DiscoveryClientResolverFactory(client, this))
                .usePlaintext(properties.getChannel(name).isPlaintext());
        if (channelProperties.isEnableKeepAlive()) {
            builder.keepAliveWithoutCalls(channelProperties.isKeepAliveWithoutCalls())
                    .keepAliveTime(channelProperties.getKeepAliveTime(), TimeUnit.SECONDS)
                    .keepAliveTimeout(channelProperties.getKeepAliveTimeout(), TimeUnit.SECONDS);
        }
        if(channelProperties.getMaxInboundMessageSize() > 0) {
        	builder.maxInboundMessageSize(channelProperties.getMaxInboundMessageSize());
        }
        Channel channel = builder.build();

        List<ClientInterceptor> globalInterceptorList = globalClientInterceptorRegistry.getClientInterceptors();
        Set<ClientInterceptor> interceptorSet = Sets.newHashSet();
        if (globalInterceptorList != null && !globalInterceptorList.isEmpty()) {
            interceptorSet.addAll(globalInterceptorList);
        }
        if (interceptors != null && !interceptors.isEmpty()) {
            interceptorSet.addAll(interceptors);
        }
        return ClientInterceptors.intercept(channel, Lists.newArrayList(interceptorSet));
    }
}
```


## 最后
纵观以上的配置，主要目的就是创建一个channel，并能够让客户端能够使用这个channel，channel顾名思义就是通道的意思，channel是grpc的一个核心模块，它有不同的实现（Netty，OkHttp）

为了创建channel，我们需要引入和服务注册中心交互的模块DiscoveryClientNameResolver，同时为了维持channel通道的稳定，我们还需要在DiscoveryClientNameResolver中配置任务，不断轮询注册中心，获得最新的服务端配置

为了能够在channel工作的时候做拦截处理，例如记录日志，权限验证，我们还需要使用GlobalClientInterceptorRegistry加入我们的自定义实现

最后，还需要知道客户端需要调取哪个服务端的服务，并将创建好的channel交还给客户端使用，所以就有了GrpcClientBeanPostProcessor模块




客户端的实现形式如下

```
@Service
public class GrpcClientService {

    //消费自身的服务
    @GrpcClient("toto")
    private Channel serverChannel;

    public String sendMessage(String name) {
        SimpleGrpc.SimpleBlockingStub stub = SimpleGrpc.newBlockingStub(serverChannel);
        HelloReply response = stub.sayHello(HelloRequest.newBuilder().setName(name).build());
        return response.getMessage();
    }
}
```




