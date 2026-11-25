package com.hmdp.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BusinessTimeConfig {
  @Bean
  public Jackson2ObjectMapperBuilderCustomizer utcDates() {
    // DB 中无偏移的 UTC 时间输出时补 Z，浏览器才不会把活动时间当成本地时间。
    return builder ->
        builder.serializerByType(
            LocalDateTime.class,
            new JsonSerializer<LocalDateTime>() {
              @Override
              public void serialize(
                  LocalDateTime value, JsonGenerator generator, SerializerProvider provider)
                  throws IOException {
                generator.writeString(value.atOffset(ZoneOffset.UTC).toString());
              }
            });
  }
}
