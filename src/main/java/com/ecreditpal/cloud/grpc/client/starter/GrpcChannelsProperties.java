package com.ecreditpal.cloud.grpc.client.starter;

import com.google.common.collect.Maps;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.Map;

/**
 * GrpcChannelsProperties 和 GrpcChannelProperties 不要混淆了
 * GrpcChannelsProperties可以得到用户对于特定channel的配置
 *
 * 通过key:channelName, value:GrpcChannelProperties的方式映射
 * 如果没有做过配置,使用默认的GrpcChannelProperties配置
 */
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
