## Quick Usage

Define your .proto class:
```
syntax = "proto3";
package ts;

message Test {
  uint32 myId = 1;
  float distance = 2;
  float weight = 3;
  float price = 4;
  InnerPart inner = 5;
}

message InnerPart {
  repeated uint32 innerMyId = 1;
  repeated float width = 2;
  repeated float length = 3;
  repeated bool isRunning = 4;
  repeated string info = 5;
}
```

## Default usage.
```
    @Test
    void testCase() throws Exception {

        // Create registry
        ClassConventionRegister cRegister = new ClassConventionRegister();

        // Register classes
        cRegister.registerClass(
                TestDto.class,
                TestOuterClass.Test.class // generated class by protoc
        );

        cRegister.registerClass(
                InnerPart.class,
                InnerOuterPart.Test.class // generated class by protoc
        );


        // Create services
        ProtobufSerializationService protobufSerializationService = new ProtobufSerializationService(cRegister.getSRegistry());
        ProtobufDeserializationService protobufDeserializationService = new ProtobufDeserializationService(cRegister.getDRegistry());

        TestDto originalTestDto = new TestDto(
                1,
                1.22f,
                2.23f,
                2.24f,
                new InnerPart(
                        List.of(1, 2, 3),
                        List.of(99.99f, 77.77f),
                        List.of(88.88f, 111.11f),
                        List.of(true, false, true, false),
                        List.of("hello", "bye", "nah")
                )
        );

        log.info("Before: {}", originalTestDto);
        byte[] bytes = protobufSerializationService.serialize(originalTestDto);
        TestDto deserializedTestDto = protobufDeserializationService.deserialize(bytes, TestOuterClass.Test.class);
        log.info("After:  {}", deserializedTestDto);

        // Checks
        assertNotNull(bytes, "Serialized data should not be null");
        assertNotNull(deserializedTestDto, "Deserialized object should not be null");
        assertEquals(originalTestDto, deserializedTestDto, "The deserialized object should match the original object");
    }
```
## Usage with Spring Boot.

Define `ClassConventionRegister` in code
```
@Configuration
public class TestConfig {

    ClassConventionRegister cRegister = new ClassConventionRegister();

    @Bean
    public ClassConventionRegister classConventionRegister() {
        // Register classes
        cRegister.registerClass(
                TestDto.class,
                TestOuterClass.Test.class // generated class by protoc
        );

        cRegister.registerClass(
                InnerPart.class,
                InnerOuterPart.Test.class // generated class by protoc
        );
    }
}
```
Define your class (example):
```
@RequiredArgsConstructor
public class TestController {

    private final ProtobufSerializationService protobufSerializationService;
    private final ProtobufDeserializationService protobufDeserializationService;

    public void myFoo() {

      TestDto testDto = new TestDto(
              1,
              1.22f,
              2.23f,
              2.24f,
              new InnerPart(
                      List.of(1, 2, 3),
                      List.of(99.99f, 77.77f),
                      List.of(88.88f, 111.11f),
                      List.of(true, false, true, false),
                      List.of("hello", "bye", "nah")
              )
      );
  
      // use the same logic for converting to bytes and back
      byte[] bytes1 = protobufSerializationService.serialize(testDto); // for example convert into bytes and send to another server
      TestDto test2;
      test2 = protobufDeserializationService.deserialize(bytes1, TestOuterClass.Test.class); // received bytes from another server, but using defined .proto scheme to deserialize to Java object

    }
}
```

The code has support for conversion:
1) Complex nested objects
2) Arrays and Lists of primtives and objects
3) String, Timestamp/LDT, enum types

## Restrictions of this library
1) You must not define you variables in .proto file with prefix `is` (these are usually variables of boolean type). Using them in your POJO is ok.
2) This code still doesn't support `Map<>` collections.

