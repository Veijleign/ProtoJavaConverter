package com.test.common;

import com.google.protobuf.Message;
import com.test.deserializer.DeserializerRegistry;
import com.test.serializer.SerializerRegistry;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ClassConventionRegister {

    private final DeserializerRegistry dRegistry = new DeserializerRegistry();
    private final SerializerRegistry sRegistry = new SerializerRegistry();

    public <T> void registerClass(
            Class<T> javaClass,
            Class<? extends Message> protoClass
    ) {
        sRegistry.registerSerializer(
                javaClass,
                protoClass
        );

        dRegistry.registerDeserializer(
                javaClass,
                protoClass
        );
    }
}