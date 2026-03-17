# Tekton 实践指南：从 Java 项目到 CI/CD 流水线

从零搭建基于 Tekton 的 CI/CD 流水线：Java 项目 → 容器化 → GitHub 托管 → Minikube 集群 → Tekton 流水线 → 自动构建部署。

- **GitHub 仓库**: https://github.com/Cocola6s6/tekton.git
- **Docker Hub 镜像**: docker.io/cocola6s6/tekton:latest
- **JDK 版本**: 11

---

## 目录

1. [创建 Java 项目](#1-创建-java-项目)
2. [编写 Dockerfile](#2-编写-dockerfile)
3. [上传代码到 GitHub](#3-上传代码到-github)
4. [搭建 Kubernetes 集群（Minikube）](#4-搭建-kubernetes-集群minikube)
5. [安装 Tekton](#5-安装-tekton)
6. [配置 Docker Hub 凭证](#6-配置-docker-hub-凭证)
7. [配置 RBAC 权限](#7-配置-rbac-权限)
8. [定义 Tekton Task](#8-定义-tekton-task)
9. [定义 Tekton Pipeline](#9-定义-tekton-pipeline)
10. [触发 PipelineRun](#10-触发-pipelinerun)
11. [实践中遇到的问题与解决](#11-实践中遇到的问题与解决)

---

## Tekton CI/CD 流程图

### 整体架构

```
┌─────────────┐     git push      ┌─────────────┐
│  开发者本地   │ ───────────────► │   GitHub     │
│  Java 项目   │                  │   仓库       │
└─────────────┘                   └──────┬───────┘
                                         │
                           kubectl create pipelinerun
                                         │
                                         ▼
┌────────────────────────────────────────────────────────────────┐
│                    Kubernetes 集群 (Minikube)                   │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              Tekton Pipeline: java-build-deploy-pipeline           │  │
│  │                                                          │  │
│  │  ┌──────────────┐   ┌──────────────┐   ┌─────────────┐  │  │
│  │  │ Task 1       │   │ Task 2       │   │ Task 3      │  │  │
│  │  │              │   │              │   │             │  │  │
│  │  │ git-clone    │──►│ build-push   │──►│ deploy      │  │  │
│  │  │              │   │ (Kaniko)     │   │ (kubectl)   │  │  │
│  │  │ 拉取代码     │   │ 构建+推送镜像 │   │ 部署到集群  │  │  │
│  │  └──────┬───────┘   └──────┬───────┘   └──────┬──────┘  │  │
│  │         │                  │                   │         │  │
│  └─────────┼──────────────────┼───────────────────┼─────────┘  │
│            │                  │                   │            │
│            ▼                  ▼                   ▼            │
│     ┌────────────┐    ┌────────────┐     ┌──────────────┐     │
│     │ GitHub     │    │ Docker Hub │     │ Deployment   │     │
│     │ 仓库代码    │    │ 镜像仓库   │     │ + Service    │     │
│     └────────────┘    └────────────┘     └──────────────┘     │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### Pipeline 执行流程

```
PipelineRun 触发
       │
       ▼
┌──────────────┐  成功   ┌──────────────┐  成功   ┌──────────────┐
│  fetch-source │──────►│ build-push   │──────►│  deploy-app  │
│              │        │  -image      │        │              │
│  alpine/git  │        │  Kaniko      │        │  kubectl     │
│  克隆仓库代码 │        │  构建 Docker  │        │  创建        │
│              │        │  镜像并推送到 │        │  Deployment  │
│              │        │  Docker Hub  │        │  + Service   │
└──────────────┘        └──────────────┘        └──────┬───────┘
                                                       │
                                                       ▼
                                                 应用部署完成
                                              kubectl get deployment
                                              kubectl port-forward
```

### Tekton 核心概念关系

```
Pipeline                    ── 定义完整的 CI/CD 流程（编排多个 Task）
  │
  ├── Task: git-clone       ── 一个独立的工作单元（拉取代码）
  │     └── Step: clone     ── Task 中的具体执行步骤（运行 git clone）
  │
  ├── Task: build-push      ── 构建并推送镜像
  │     └── Step: kaniko    ── 使用 Kaniko 构建容器镜像
  │
  └── Task: deploy          ── 部署到 K8s
        └── Step: kubectl   ── 执行 kubectl apply

PipelineRun                 ── Pipeline 的一次具体执行实例
  ├── TaskRun: fetch-source ── 对应 Task 的一次执行
  ├── TaskRun: build-push   ── 每个 TaskRun 创建一个 Pod
  └── TaskRun: deploy-app   ── Pod 中的容器对应 Step

Workspace                   ── Task 之间共享数据的存储卷 (PVC)
Secret                      ── 存储 Docker Hub 凭证
```

---

## 1. 创建 Java 项目

### 1.1 项目结构（Maven）

```
tekton/
├── pom.xml
├── Dockerfile
├── .gitignore
├── src/
│   └── main/
│       └── java/
│           └── com/example/
│               └── App.java
└── tekton/
    ├── tasks/
    │   ├── git-clone.yaml
    │   ├── build-push.yaml
    │   └── deploy.yaml
    ├── pipeline.yaml
    ├── pipelinerun.yaml
    └── rbac-pipeline-runner.yaml
```

### 1.2 `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>tekton</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.3.0</version>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>com.example.App</mainClass>
                        </manifest>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### 1.3 主类 `App.java`

```java
package com.example;

public class App {
    public static void main(String[] args) {
        System.out.println("Hello from Tekton CI/CD!");
    }
}
```

### 1.4 本地验证

```bash
mvn clean package
java -jar target/tekton-1.0.0.jar
```

---

## 2. 编写 Dockerfile

采用多阶段构建，使用 `focal` 基础镜像以兼容 Apple Silicon (arm64)：

```dockerfile
# 阶段一：构建
FROM maven:3.9-eclipse-temurin-11-focal AS builder
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -B

# 阶段二：运行
FROM eclipse-temurin:11-jre-focal
WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

本地构建验证：

```bash
docker build -t tekton:latest .
docker run --rm tekton:latest
```

---

## 3. 上传代码到 GitHub

```bash
git init
echo "target/" >> .gitignore
echo ".idea/" >> .gitignore
git add .
git commit -m "Initial commit: Java app with Dockerfile and Tekton"

git remote add origin https://github.com/Cocola6s6/tekton.git
git branch -M master
git push -u origin master
```

---

## 4. 搭建 Kubernetes 集群（Minikube）

### 4.1 安装

```bash
brew install minikube
```

或从 [minikube 官网](https://minikube.sigs.k8s.io/docs/start/) 下载。

### 4.2 启动集群

```bash
minikube start --driver=docker --cpus=2 --memory=4096

kubectl cluster-info
kubectl get nodes
```

---

## 5. 安装 Tekton

### 5.1 安装 Tekton Pipelines

```bash
kubectl apply -f https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml
```

### 5.2 等待就绪

```bash
kubectl wait --for=condition=Ready pods --all -n tekton-pipelines --timeout=300s
kubectl get pods -n tekton-pipelines
```

### 5.3 可选：安装 Tekton Dashboard

```bash
kubectl apply -f https://storage.googleapis.com/tekton-releases/dashboard/latest/release.yaml

# 等待 Dashboard Pod 就绪后再执行端口转发
kubectl wait --for=condition=Ready pods -l app.kubernetes.io/part-of=tekton-dashboard -n tekton-pipelines --timeout=120s
kubectl port-forward -n tekton-pipelines svc/tekton-dashboard 9097:9097
# 浏览器访问 http://localhost:9097
```

> 注意：Dashboard 安装 URL 为 `release.yaml`，旧版文档中的 `tekton-dashboard-release.yaml` 已失效（返回 404）。

---

## 6. 配置 Docker Hub 凭证

```bash
kubectl create secret docker-registry dockerhub-secret \
  --docker-server=https://index.docker.io/v1/ \
  --docker-username=cocola6s6 \
  --docker-password=<your token> \
  --docker-email=yangshiyuan6s6@gmail.com \
  -n default
```

> 注意：`--docker-username` 必须使用**全小写**，否则 Kaniko 推送会报错。建议使用 [Docker Hub Access Token](https://hub.docker.com/settings/security) 代替密码。

---

## 7. 配置 RBAC 权限

deploy 步骤需要 default ServiceAccount 有权限创建 Deployment 和 Service：

```yaml
# tekton/rbac-pipeline-runner.yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: tekton-pipeline-runner
  namespace: default
rules:
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: [""]
    resources: ["services", "pods", "pods/log"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: tekton-pipeline-runner
  namespace: default
subjects:
  - kind: ServiceAccount
    name: default
    namespace: default
roleRef:
  kind: Role
  name: tekton-pipeline-runner
  apiGroup: rbac.authorization.k8s.io
```

应用：

```bash
kubectl apply -f tekton/rbac-pipeline-runner.yaml
```

---

## 8. 定义 Tekton Task

### Workspace：Task 之间怎么共享数据？

三个 Task 运行在不同的 Pod 中，彼此看不到对方的文件。要让 git-clone 拉下来的代码能被 build-push 读到，就需要一块**共享硬盘**——这就是 Workspace。

**Workspace 本质上就是一个 PVC（持久化存储卷）。** 每次触发 PipelineRun 时，Tekton 自动创建一个 1Gi 的 PVC，三个 Task 的 Pod 轮流挂载它：

```
kubectl create -f pipelinerun.yaml
         │
         ▼
  Tekton 自动创建 PVC (1Gi)   ← 这就是那块 "共享硬盘"
         │
         ├──► git-clone Pod     挂载 PVC → 把代码写进去
         │
         ├──► build-push Pod    挂载 PVC → 读代码，构建镜像
         │
         └──► deploy Pod        挂载 PVC → 可选，读取 manifest
```

#### 为什么 Workspace 名字不一样？

每个 Task 是独立定义的，各自按职责给 Workspace 取名：
- git-clone 叫它 `output`（"我的输出目录"）
- build-push 叫它 `source`（"我的源码目录"）

名字不同没关系，**Pipeline 负责把它们指向同一个 PVC**：

```yaml
# pipeline.yaml 中的映射
tasks:
  - name: fetch-source
    workspaces:
      - name: output              # git-clone 的叫法
        workspace: shared-workspace   # ──┐
                                          │  同一个 PVC
  - name: build-push-image                │
    workspaces:                           │
      - name: source              # build-push 的叫法
        workspace: shared-workspace   # ──┘
```

#### PVC 从哪来？

在 PipelineRun 中通过 `volumeClaimTemplate` 定义，Tekton 会自动创建：

```yaml
# pipelinerun.yaml
workspaces:
  - name: shared-workspace        # 逻辑名（Pipeline 里引用的）
    volumeClaimTemplate:           # 告诉 Tekton：帮我新建一个 PVC
      spec:
        accessModes:
          - ReadWriteOnce
        resources:
          requests:
            storage: 1Gi
```

Tekton 创建的 PVC 名字会带编号（如 `pvc-3a7f8b2c-xxxx`），你不需要关心这个编号，Tekton 内部自动管理映射关系。通过 `kubectl get pvc` 可以查看。

#### Task 中怎么使用？

在 Task 的 step 中，用 `$(workspaces.<name>.path)` 引用挂载路径，Tekton 自动解析为 `/workspace/<name>`：

```bash
# git-clone 中
git clone ... $(workspaces.output.path)
# 实际执行的是：git clone ... /workspace/output

# build-push 中
--context=$(workspaces.source.path)
# 实际执行的是：--context=/workspace/source
```

### 8.1 拉取代码：`git-clone.yaml`

```yaml
# tekton/tasks/git-clone.yaml
apiVersion: tekton.dev/v1beta1
kind: Task
metadata:
  name: git-clone
spec:
  params:
    - name: url
      description: 仓库 URL
    - name: revision
      default: master
    - name: deleteExisting
      default: "true"
  workspaces:
    - name: output
      description: 克隆后的代码目录
  steps:
    - name: clone
      image: alpine/git:latest
      script: |
        #!/bin/sh
        set -e
        git clone $(params.url) --branch $(params.revision) --single-branch $(workspaces.output.path)
        cd $(workspaces.output.path)
        git rev-parse HEAD
```

### 8.2 构建推送镜像：`build-push.yaml`

#### 为什么使用 Kaniko？

在本地开发时，我们用 Docker CLI 构建和推送镜像：

```bash
docker build -t cocola6s6/tekton:latest .    # 构建镜像
docker login                                  # 登录 Docker Hub
docker push cocola6s6/tekton:latest           # 推送镜像
```

但在 Kubernetes 集群内的 Pod 中，**没有 Docker Daemon 运行**，无法直接执行 `docker build`。Kaniko 就是用来解决这个问题的：

| | 本地（Docker CLI） | 集群内（Kaniko） |
|---|---|---|
| 构建镜像 | `docker build` | Kaniko executor `--context` + `--dockerfile` |
| 登录仓库 | `docker login` | 挂载 `dockerhub-secret` 为 config.json |
| 推送镜像 | `docker push` | Kaniko executor `--destination` |
| 依赖 | 需要 Docker Daemon | 不需要，在普通容器中直接运行 |

Kaniko 将 `docker build` + `docker login` + `docker push` 三步合为一步，通过 `args` 传参给 `/kaniko/executor` 二进制完成所有操作：

```
/kaniko/executor \
  --dockerfile=Dockerfile \                           # 等同于 docker build -f
  --context=/workspace/source \                       # 等同于 docker build 的上下文目录
  --destination=docker.io/cocola6s6/tekton:latest \   # 等同于 docker tag + docker push
  --skip-tls-verify
```

使用 Kaniko 在集群内构建并推送到 Docker Hub：

```yaml
# tekton/tasks/build-push.yaml
apiVersion: tekton.dev/v1beta1
kind: Task
metadata:
  name: build-push
spec:
  params:
    - name: IMAGE
      description: 完整镜像地址，如 docker.io/cocola6s6/tekton
    - name: DOCKERFILE
      default: Dockerfile
  workspaces:
    - name: source
      description: 源代码目录（含 Dockerfile）
  steps:
    - name: build-and-push
      image: gcr.io/kaniko-project/executor:v1.19.0
      env:
        - name: DOCKER_CONFIG
          value: /tekton/home/.docker
      args:
        - --dockerfile=$(params.DOCKERFILE)
        - --context=$(workspaces.source.path)
        - --destination=$(params.IMAGE)
        - --skip-tls-verify
      volumeMounts:
        - name: docker-config
          mountPath: /tekton/home/.docker
  volumes:
    - name: docker-config
      secret:
        secretName: dockerhub-secret
        items:
          - key: .dockerconfigjson
            path: config.json
```

### 8.3 部署到 Kubernetes：`deploy.yaml`

```yaml
# tekton/tasks/deploy.yaml
apiVersion: tekton.dev/v1beta1
kind: Task
metadata:
  name: deploy
spec:
  params:
    - name: IMAGE
      description: 要部署的镜像地址
    - name: NAMESPACE
      default: default
  workspaces:
    - name: manifest-dir
      description: 存放 k8s manifest 的目录（可选）
      optional: true
  steps:
    - name: apply
      image: bitnami/kubectl:latest
      script: |
        #!/bin/sh
        set -e
        cat <<EOF | kubectl apply -f -
        apiVersion: apps/v1
        kind: Deployment
        metadata:
          name: tekton
          namespace: $(params.NAMESPACE)
        spec:
          replicas: 1
          selector:
            matchLabels:
              app: tekton
          template:
            metadata:
              labels:
                app: tekton
            spec:
              containers:
                - name: app
                  image: $(params.IMAGE)
                  ports:
                    - containerPort: 8080
        ---
        apiVersion: v1
        kind: Service
        metadata:
          name: tekton
          namespace: $(params.NAMESPACE)
        spec:
          selector:
            app: tekton
          ports:
            - port: 80
              targetPort: 8080
        EOF
```

### 8.4 应用所有 Task

```bash
kubectl apply -f tekton/tasks/git-clone.yaml
kubectl apply -f tekton/tasks/build-push.yaml
kubectl apply -f tekton/tasks/deploy.yaml
```

---

## 9. 定义 Tekton Pipeline

编排流程：拉代码 → 构建推送 → 部署。

```yaml
# tekton/pipeline.yaml
apiVersion: tekton.dev/v1beta1
kind: Pipeline
metadata:
  name: java-build-deploy-pipeline
spec:
  params:
    - name: GIT_URL
      type: string
    - name: GIT_REVISION
      default: master
    - name: IMAGE
      type: string
    - name: NAMESPACE
      default: default
  workspaces:
    - name: shared-workspace
    - name: docker-credentials
      optional: true
  tasks:
    - name: fetch-source
      taskRef:
        name: git-clone
      params:
        - name: url
          value: $(params.GIT_URL)
        - name: revision
          value: $(params.GIT_REVISION)
      workspaces:
        - name: output
          workspace: shared-workspace

    - name: build-push-image
      taskRef:
        name: build-push
      runAfter:
        - fetch-source
      params:
        - name: IMAGE
          value: $(params.IMAGE)
      workspaces:
        - name: source
          workspace: shared-workspace

    - name: deploy-app
      taskRef:
        name: deploy
      runAfter:
        - build-push-image
      params:
        - name: IMAGE
          value: $(params.IMAGE)
        - name: NAMESPACE
          value: $(params.NAMESPACE)
```

应用：

```bash
kubectl apply -f tekton/pipeline.yaml
```

---

## 10. 触发 PipelineRun

### 10.1 PipelineRun 定义

```yaml
# tekton/pipelinerun.yaml
apiVersion: tekton.dev/v1beta1
kind: PipelineRun
metadata:
  generateName: java-build-deploy-pipeline-
spec:
  pipelineRef:
    name: java-build-deploy-pipeline
  params:
    - name: GIT_URL
      value: "https://github.com/Cocola6s6/tekton.git"
    - name: GIT_REVISION
      value: master
    - name: IMAGE
      value: "docker.io/cocola6s6/tekton:latest"
    - name: NAMESPACE
      value: default
  workspaces:
    - name: shared-workspace
      volumeClaimTemplate:
        spec:
          accessModes:
            - ReadWriteOnce
          resources:
            requests:
              storage: 1Gi
  timeout: 20m
```

### 10.2 触发

```bash
kubectl create -f tekton/pipelinerun.yaml
```

### 10.3 查看运行状态

```bash
kubectl get pipelinerun
kubectl get taskrun
kubectl get pods

# 查看日志
tkn pipelinerun logs <pipelinerun-name> -f
```

### 10.4 验证部署

```bash
kubectl get deployment tekton
kubectl get svc tekton
kubectl port-forward svc/tekton 8080:80
```

端口转发启动后，访问以下接口自测：

| 接口 | 方式 | 预期响应 |
|------|------|----------|
| `http://localhost:8080/` | GET | `Hello from Tekton CI/CD!` |
| `http://localhost:8080/health` | GET | `{"status":"UP","service":"tekton","time":"..."}` |

```bash
# 测试首页
curl http://localhost:8080/
# 返回: Hello from Tekton CI/CD!

# 测试健康检查接口
curl http://localhost:8080/health
# 返回: {"status":"UP","service":"tekton","time":"2026-03-17T13:57:49.215876"}
```

如果两个接口都正常返回，说明整条 CI/CD 流水线（拉代码 → 构建镜像 → 推送 → 部署）全部跑通。

---

## 11. 实践中遇到的问题与解决

### 问题 1：Dockerfile 构建失败 — Alpine 镜像不支持 arm64

**报错信息：**

```
ERROR: failed to solve: maven:3.9-eclipse-temurin-11-alpine: no match for platform in manifest: not found
ERROR: failed to solve: eclipse-temurin:11-jre-alpine: no match for platform in manifest: not found
```

**原因：** Apple Silicon (M1/M2) 是 arm64 架构，而 Alpine 系列的 JDK 11 镜像只提供 amd64 版本。

**解决：** 将基础镜像从 `alpine` 改为 `focal`（Ubuntu 20.04），后者支持多架构：

```dockerfile
# 改前
FROM maven:3.9-eclipse-temurin-11-alpine AS builder
FROM eclipse-temurin:11-jre-alpine

# 改后
FROM maven:3.9-eclipse-temurin-11-focal AS builder
FROM eclipse-temurin:11-jre-focal
```

---

### 问题 2：Kaniko 推送失败 — Docker Hub 仓库名大写

**报错信息：**

```
error checking push permissions -- repository can only contain the characters `abcdefghijklmnopqrstuvwxyz0123456789_-./`: Cocola6s6/tekton
```

**原因：** Docker Hub 镜像仓库名**只允许小写字母**，`Cocola6s6` 中的大写 `C` 不合法。

**解决：** 将 IMAGE 参数改为全小写 `docker.io/cocola6s6/tekton:latest`。

---

### 问题 3：Kaniko 推送失败 — 401 Unauthorized

**报错信息：**

```
unexpected status code 401 Unauthorized: {"details":"incorrect username or password"}
```

**原因：** `dockerhub-secret` 中的用户名或密码不正确。

**解决：** 删除并重建 Secret，确保用户名为**全小写**：

```bash
kubectl delete secret dockerhub-secret -n default
kubectl create secret docker-registry dockerhub-secret \
  --docker-server=https://index.docker.io/v1/ \
  --docker-username=cocola6s6 \
  --docker-password=<your token> \
  --docker-email=yangshiyuan6s6@gmail.com \
  -n default
```

---

### 问题 4：deploy 步骤失败 — RBAC 权限不足

**报错信息：**

```
deployments.apps "tekton" is forbidden: User "system:serviceaccount:default:default" cannot get resource "deployments"
```

**原因：** default ServiceAccount 没有权限在 default 命名空间创建 Deployment 和 Service。

**解决：** 应用 RBAC 配置（见[第 7 步](#7-配置-rbac-权限)）：

```bash
kubectl apply -f tekton/rbac-pipeline-runner.yaml
```

---

### 问题 5：Minikube 重启后 Task/Pipeline/RBAC 丢失

**现象：** PipelineRun 报 `CouldntGetPipeline`，`kubectl get pipeline` 返回空。

**原因：** Minikube 重启后集群中的自定义资源可能被清除。

**解决：** 重新应用所有资源：

```bash
kubectl apply -f tekton/tasks/git-clone.yaml
kubectl apply -f tekton/tasks/build-push.yaml
kubectl apply -f tekton/tasks/deploy.yaml
kubectl apply -f tekton/pipeline.yaml
kubectl apply -f tekton/rbac-pipeline-runner.yaml
```

---

### 问题 6：应用 Pod 处于 CrashLoopBackOff

**现象：** `kubectl get pods` 显示 Pod 状态为 CrashLoopBackOff。

**原因：** 当前 `App.java` 只打印一行后就退出了，不是常驻进程。Kubernetes 认为容器异常退出，会不断重启。

**说明：** 这是**预期行为**，不影响流水线验证。若需要 Pod 持续运行，可将 `App.java` 改为 HTTP 服务（如 Spring Boot）。

---

### 问题 7：Tekton Dashboard 安装 URL 返回 404

**报错信息：**

```
error: unable to read URL "https://storage.googleapis.com/tekton-releases/dashboard/latest/tekton-dashboard-release.yaml", server reported 404 Not Found
```

**原因：** Tekton Dashboard 的安装文件名已从 `tekton-dashboard-release.yaml` 改为 `release.yaml`。

**解决：** 使用新的 URL：

```bash
kubectl apply -f https://storage.googleapis.com/tekton-releases/dashboard/latest/release.yaml
```

---

### 问题 8：Dashboard 端口转发失败 — Pod 未就绪

**报错信息：**

```
error: unable to forward port because pod is not running. Current status=Pending
```

**原因：** Dashboard Pod 还在拉取镜像或初始化中，尚未进入 Running 状态。

**解决：** 等待 Pod 就绪后再执行端口转发：

```bash
kubectl wait --for=condition=Ready pods -l app.kubernetes.io/part-of=tekton-dashboard -n tekton-pipelines --timeout=120s
kubectl port-forward -n tekton-pipelines svc/tekton-dashboard 9097:9097
```

---

## 参考链接

- [Tekton Pipelines 官方文档](https://tekton.dev/docs/pipelines/)
- [Tekton Task 与 Pipeline 概念](https://tekton.dev/docs/concepts/)
- [Minikube 文档](https://minikube.sigs.k8s.io/docs/)
- [Kaniko 构建镜像](https://github.com/GoogleContainerTools/kaniko)
