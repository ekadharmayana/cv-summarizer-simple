FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY src ./src

RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip \
    && rm -rf /var/lib/apt/lists/*

COPY python/requirements.txt /tmp/requirements.txt
RUN pip3 install --no-cache-dir -r /tmp/requirements.txt

COPY --from=build /workspace/target/cv-summarizer-backend-0.0.1-SNAPSHOT.jar /app/app.jar
COPY python /app/python

ENV CVSUM_PYTHON_EXECUTABLE=python3
ENV CVSUM_PYTHON_SCRIPT_PATH=python/gpu_infer.py
ENV CVSUM_PYTHON_TIMEOUT_SECONDS=60
ENV HF_MODEL_ID=TinyLlama/TinyLlama-1.1B-Chat-v1.0

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
