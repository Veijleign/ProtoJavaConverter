package com.test.deserializer;

import com.google.protobuf.Message;

import java.lang.reflect.Method;

public class ProtobufDeserializationService {
    private final DeserializerRegistry deserializerRegistry;

    public ProtobufDeserializationService(DeserializerRegistry deserializerRegistry) {
        this.deserializerRegistry = deserializerRegistry;
    }

    public <T> T deserialize(byte[] data, Class<? extends Message> protoClass) throws Exception {

        // Парсинг байтов в Protobuf сообщение
        Method parseMethod = protoClass.getMethod("parseFrom", byte[].class);
        Message protoMessage = (Message) parseMethod.invoke(null, data);

        // Поиск десериализатора

        Class<?> specifiedJavaClass = deserializerRegistry.getJavaClassForProto(protoClass);
        ProtobufDeserializer<T> deserializer = (ProtobufDeserializer<T>)
                deserializerRegistry.getDeserializer(specifiedJavaClass);
        if (deserializer == null) {
            throw new RuntimeException("No deserializer registered for " + specifiedJavaClass);
        }

        return deserializer.deserialize(protoMessage);
    }
}
