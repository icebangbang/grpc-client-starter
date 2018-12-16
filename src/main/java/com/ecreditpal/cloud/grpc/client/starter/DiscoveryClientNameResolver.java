package com.ecreditpal.cloud.grpc.client.starter;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.internal.SharedResourceHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;


import javax.annotation.concurrent.GuardedBy;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

/**
 * 发现服务解析器
 */
@Slf4j
public class    DiscoveryClientNameResolver extends NameResolver {

    private final String name;
    private final DiscoveryClient client;
    private final Attributes attributes;
    private final SharedResourceHolder.Resource<ScheduledExecutorService> timerServiceResource;
    private final SharedResourceHolder.Resource<ExecutorService> executorResource;
    @GuardedBy("this")
    private boolean shutdown;
    @GuardedBy("this")
    private ScheduledExecutorService timerService;
    @GuardedBy("this")
    private ExecutorService executor;
    @GuardedBy("this")
    private ScheduledFuture<?> resolutionTask;
    @GuardedBy("this")
    private boolean resolving;
    @GuardedBy("this")
    private Listener listener;
    @GuardedBy("this")
    private List<ServiceInstance> serviceInstanceList;

    /**
     *
     * @param name 注册在注册中心的应用名称,通过它我们才能找到对应的应用
     * @param client 注册发现客户端
     * @param attributes 额外存放变量的对象
     * @param timerServiceResource 定时任务线程池
     * @param executorResource 驱动器所用的线程池
     */
    public DiscoveryClientNameResolver(String name, DiscoveryClient client, Attributes attributes, SharedResourceHolder.Resource<ScheduledExecutorService> timerServiceResource,
                                       SharedResourceHolder.Resource<ExecutorService> executorResource) {
        this.name = name;
        this.client = client;
        this.attributes = attributes;
        this.timerServiceResource = timerServiceResource;
        this.executorResource = executorResource;
        this.serviceInstanceList = Lists.newArrayList();
    }

    @Override
    public final String getServiceAuthority() {
        return name;
    }

    @Override
    public final synchronized void start(Listener listener) {
        Preconditions.checkState(this.listener == null, "already started");
        timerService = SharedResourceHolder.get(timerServiceResource);
        this.listener = listener;
        executor = SharedResourceHolder.get(executorResource);
        this.listener = Preconditions.checkNotNull(listener, "listener");
        resolve();
    }

    @Override
    public final synchronized void refresh() {
        if (listener != null) {
            resolve();
        }
    }

    private final Runnable resolutionRunnable = new Runnable() {
        @Override
        public void run() {
            Listener savedListener;
            synchronized (DiscoveryClientNameResolver.this) {
                // If this task is started by refresh(), there might already be a scheduled task.
                if (resolutionTask != null) {
                    resolutionTask.cancel(false);
                    resolutionTask = null;
                }
                if (shutdown) {
                    return;
                }
                savedListener = listener;
                resolving = true;
            }
            try {
                List<ServiceInstance> newServiceInstanceList;
                try {
                    newServiceInstanceList = client.getInstances(name);
                } catch (Exception e) {
                    savedListener.onError(Status.UNAVAILABLE.withCause(e));
                    return;
                }

                if (newServiceInstanceList!= null && newServiceInstanceList.size() > 0) {
                    if (isNeedToUpdateServiceInstanceList(newServiceInstanceList)) {
                        serviceInstanceList = newServiceInstanceList;
                    } else {
                        return;
                    }
                    List<EquivalentAddressGroup> equivalentAddressGroups = Lists.newArrayList();
                    for (ServiceInstance serviceInstance : serviceInstanceList) {
                        Map<String, String> metadata = serviceInstance.getMetadata();
                        if (metadata.get("gRPC") != null) {
                            Integer port = Integer.valueOf(metadata.get("gRPC"));
                            log.info("Found gRPC server {} {}:{}", name, serviceInstance.getHost(), port);
                            EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(new InetSocketAddress(serviceInstance.getHost(), port), Attributes.EMPTY);
                            equivalentAddressGroups.add(addressGroup);
                        } else {
                            log.error("Can not found gRPC server {}", name);
                        }
                    }
                    savedListener.onAddresses(equivalentAddressGroups, Attributes.EMPTY);
                } else {
                    savedListener.onError(Status.UNAVAILABLE.withCause(new RuntimeException("UNAVAILABLE: NameResolver returned an empty list")));
                }
            } finally {
                synchronized (DiscoveryClientNameResolver.this) {
                    resolving = false;
                }
            }
        }
    };

    private boolean isNeedToUpdateServiceInstanceList(List<ServiceInstance> newServiceInstanceList) {
        Map<String, String> rpcPorts = new HashMap<String, String>();
        for (ServiceInstance serviceInstance : newServiceInstanceList) {
            String port = serviceInstance.getMetadata().get("gRPC");
            rpcPorts.put(serviceInstance.getHost(), port);
        }

        boolean isSame = true;

        if (serviceInstanceList.size() == 0) {
            isSame = false;
        } else if (serviceInstanceList.size() != newServiceInstanceList.size()) {
            isSame = false;
        } else {

            for (ServiceInstance serviceInstance : serviceInstanceList) {
                String port = rpcPorts.get(serviceInstance.getHost());
                if (port == null) {
                    isSame = false;
                    break;
                } else if (!serviceInstance.getMetadata().get("gRPC").equals(port)) {
                    isSame = false;
                    break;
                }
            }
        }

        if (!isSame) {
            log.info("Ready to update {} server info group list", name);
            return true;
        }
        return false;


//        if (serviceInstanceList.size() == newServiceInstanceList.size()) {
//            for (ServiceInstance serviceInstance : serviceInstanceList) {
//                boolean isSame = false;
//                for (ServiceInstance newServiceInstance : newServiceInstanceList) {
//                    if (newServiceInstance.getHost().equals(serviceInstance.getHost()) && newServiceInstance.getPort() == serviceInstance.getPort()) {
//                        isSame = true;
////                        break;
//                    }
//                }
//                if (!isSame) {
//                    log.info("Ready to update {} server info group list", name);
//                    return true;
//                }
//            }
//        } else {
//            log.info("Ready to update {} server info group list", name);
//            return true;
//        }
//        return false;
    }

    @GuardedBy("this")
    private void resolve() {
        if (resolving || shutdown) {
            return;
        }
        executor.execute(resolutionRunnable);
    }

    @Override
    public void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        if (resolutionTask != null) {
            resolutionTask.cancel(false);
        }
        if (timerService != null) {
            timerService = SharedResourceHolder.release(timerServiceResource, timerService);
        }
        if (executor != null) {
            executor = SharedResourceHolder.release(executorResource, executor);
        }
    }
}
