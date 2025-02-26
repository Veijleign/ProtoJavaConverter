package com.test.serializer;

import com.google.protobuf.Message;

public class ProtobufSerializationService {
    private final SerializerRegistry serializerRegistry;

    public ProtobufSerializationService(SerializerRegistry serializerRegistry) {
        this.serializerRegistry = serializerRegistry;
    }

    public <T> byte[] serialize(T javaObject) throws Exception {
        @SuppressWarnings("unchecked")
        ProtobufSerializer<T> serializer = serializerRegistry.getSerializer((Class<T>) javaObject.getClass());
        if (serializer == null) {
            throw new RuntimeException("No serializer registered for " + javaObject.getClass());
        }
        Message protobufMessage = serializer.serialize(javaObject);
        return protobufMessage.toByteArray();
    }

}
