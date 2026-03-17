# Tekton CI/CD 实践项目

基于 Tekton 的 CI/CD 流水线实践项目。通过一个简单的 Spring Boot 应用，演示如何在 Kubernetes (Minikube) 集群中使用 Tekton 实现：**自动拉取代码 → Kaniko 构建镜像 → 推送 Docker Hub → 部署到集群**的完整流水线。

## 技术栈

- Java 11 / Spring Boot 2.7
- Tekton Pipelines
- Kaniko（集群内镜像构建）
- Minikube / Kubernetes
- Docker Hub

## 快速开始

详见 [tekton-practice-guide.md](tekton-practice-guide.md)。
