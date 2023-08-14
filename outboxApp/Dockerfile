# Step : Test and packages
FROM maven:3.8.3-amazoncorretto-17 as build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline

COPY src /build/src/
RUN mvn package -DskipTests
RUN mkdir -p target/dependency && (cd target/dependency; jar -xf ../*.jar)

# Step : Package image
FROM amazoncorretto:17
# COPY --from=build /build/target/jpademo-*.jar /app/app.jar
# ENTRYPOINT ["java","-noverify","-XX:TieredStopAtLevel=1","-jar", "/app/app.jar"]

## exploded,  faster // https://spring.io/blog/2018/12/12/how-fast-is-spring , https://github.com/dsyer/spring-boot-allocations
VOLUME /tmp
ARG DEPENDENCY=/build/target/dependency
COPY --from=build ${DEPENDENCY}/BOOT-INF/lib /app/lib
COPY --from=build ${DEPENDENCY}/META-INF /app/META-INF
COPY --from=build ${DEPENDENCY}/BOOT-INF/classes /app
ENTRYPOINT ["java","-noverify","-Dspring.jmx.enabled=false","-XX:TieredStopAtLevel=1","-Dspring.config.location=classpath:/application.properties","-cp","app:app/lib/*","com.amazonaws.samples.AwsOutboxSampleApplication"]