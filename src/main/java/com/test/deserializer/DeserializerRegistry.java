package com.test.deserializer;

import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class DeserializerRegistry {
    private final Map<Class<?>, ProtobufDeserializer<?>> deserializers = new HashMap<>();
    private final HashMap<Class<?>, Class<?>> typesConversion = new HashMap<>();
    private final Logger log = LoggerFactory.getLogger(DeserializerRegistry.class);

    /**
     * @param javaClass
     * @param protoClass
     * @param <T>
     */
    public <T> void registerDeserializer(
            Class<T> javaClass,
            Class<? extends Message> protoClass
    ) {
        ProtobufDeserializer<T> deserializer = new UniversalProtobufDeserializer<>(
                javaClass,
                protoClass,
                this
        );

        typesConversion.put(protoClass, javaClass);
        deserializers.put(javaClass, deserializer);
        log.info("Registered class conversion: Java class ({}) <-> Proto class ({})", javaClass.getSimpleName(), protoClass.getSimpleName());
    }

    /**
     * Get deserializer by Java class
     *
     * @param javaClass
     * @param <T>
     * @return
     */
    @SuppressWarnings("unchecked")
    public <T> ProtobufDeserializer<T> getDeserializer(Class<T> javaClass) {
        ProtobufDeserializer<T> deserializer = (ProtobufDeserializer<T>) deserializers.get(javaClass);
        if (deserializer == null) {
            log.warn("No deserializer found for class: {}", javaClass.getName());
        }
        return deserializer;
    }

    // get java classFrom proto

    /**
     * Обратный маппинг
     *
     * @param protoClass
     * @return
     */
    public Class<?> getJavaClassForProto(Class<? extends Message> protoClass) {
        Class<?> javaClass = typesConversion.get(protoClass);
        if (javaClass == null) {
            log.warn("No Java class found for Proto class: {}", protoClass.getSimpleName());
        }
        return javaClass;
    }
}
