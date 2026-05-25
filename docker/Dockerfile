FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    git \
    curl \
    python3 \
    python3-pip \
    nodejs \
    npm \
    openjdk-21-jdk \
    maven \
    && rm -rf /var/lib/apt/lists/*

RUN pip3 install ruff --break-system-packages

WORKDIR /workspace

CMD ["bash"]