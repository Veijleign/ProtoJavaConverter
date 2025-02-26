package com.test.serializer;

import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Реестр сериализаторов. Используется для регистрации и хранения сериализаторов
 */
public class SerializerRegistry {
    private final Map<Class<?>, ProtobufSerializer<?>> serializers = new HashMap<>();
    private final Logger log = LoggerFactory.getLogger(SerializerRegistry.class);

    /**
     * Добавляет сериализатор в мапу serializers, ключом является класс Java, значением - сериализатор для этого класса
     * @param javaClass
     * @param protoClass
     * @param <T>
     */
    public <T> void registerSerializer(
            Class<T> javaClass,
            Class<? extends Message> protoClass
    ) {
        ProtobufSerializer<T> serializer = new UniversalProtobufSerializer<>(
                javaClass,
                protoClass,
                this
        );
        serializers.put(javaClass, serializer);
    }

    /**
     * Возвращает сериализатор для указанного Java класса, используя маппинг
     * @param javaClass
     * @return
     * @param <T>
     */
    @SuppressWarnings("unchecked")
    public <T> ProtobufSerializer<T> getSerializer(Class<T> javaClass) {
        ProtobufSerializer<T> serializer = (ProtobufSerializer<T>) serializers.get(javaClass);
        if (serializer == null) {
            log.warn("No serializer found for class: {}", javaClass.getName());
        }
        return serializer;
    }

}

