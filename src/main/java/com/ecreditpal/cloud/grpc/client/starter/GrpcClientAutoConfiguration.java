package com.ecreditpal.cloud.grpc.client.starter;

import io.grpc.LoadBalancer;
import io.grpc.util.RoundRobinLoadBalancerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.composite.CompositeDiscoveryClientAutoConfiguration;
import org.springframework.cloud.netflix.eureka.EurekaDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@AutoConfigureAfter(CompositeDiscoveryClientAutoConfiguration.class)
//@Configuration
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

//    @ConditionalOnMissingBean(value = GrpcChannelFactory.class, type = "org.springframework.cloud.client.discovery.DiscoveryClient")
//    @Bean
//    public GrpcChannelFactory addressChannelFactory(GrpcChannelsProperties channels, LoadBalancer.Factory loadBalancerFactory, GlobalClientInterceptorRegistry globalClientInterceptorRegistry) {
//        return new AddressChannelFactory(channels, loadBalancerFactory, globalClientInterceptorRegistry);
//    }

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

//    @Configuration
//    @ConditionalOnProperty(value = "spring.sleuth.scheduled.enabled", matchIfMissing = true)
//    @ConditionalOnClass(Tracer.class)
//    protected static class TraceClientAutoConfiguration {
//
//        @Bean
//        public BeanPostProcessor clientInterceptorPostProcessor(GlobalClientInterceptorRegistry registry) {
//            return new ClientInterceptorPostProcessor(registry);
//        }
//
//        private static class ClientInterceptorPostProcessor implements BeanPostProcessor {
//
//            private GlobalClientInterceptorRegistry registry;
//
//            public ClientInterceptorPostProcessor(
//                GlobalClientInterceptorRegistry registry) {
//                this.registry = registry;
//            }
//
//            @Override
//            public Object postProcessBeforeInitialization(Object bean,
//                String beanName)
//                throws BeansException {
//                return bean;
//            }
//
//            @Override
//            public Object postProcessAfterInitialization(Object bean,
//                String beanName)
//                throws BeansException {
//                if (bean instanceof Tracer) {
//                    this.registry.addClientInterceptors(new TraceClientInterceptor((Tracer) bean, new MetadataInjector()));
//                }
//                return bean;
//            }
//        }
//    }

}
