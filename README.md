# 企业级智能知识问答系统

> 基于RAG架构的智能知识管理平台，为企业提供高效、准确的文档问答服务

## 📋 项目简介

这是一个基于RAG（Retrieval-Augmented Generation）架构的企业级智能知识问答系统。系统支持多格式文档上传、智能语义检索和流式对话问答，通过多级缓存和智能路由机制，显著提升知识检索效率和问答准确性。

## 🏗️ 系统架构

### 整体架构图

```mermaid
graph TB
    %% 用户层
    User[用户] --> API[API接口]
    
    %% 核心服务层
    API --> Upload[文档上传]
    API --> Chat[智能问答]
    
    %% 文档处理流程
    Upload --> Processor[文档处理器]
    Processor[文档处理器] --> Splitter[文本分割]
    Splitter --> Embedding[向量化]
    Embedding --> VectorDB[(向量数据库)]
    Splitter[文本分割] --> Metadata[(MySQL元数据)]
    
    %% 问答处理流程
    Chat --> Cache[缓存检查]
    Cache --> |未命中| Search[知识检索]
    Search --> Rerank[重排序]
    Rerank --> LLM[大模型生成]
    LLM --> Answer
    
    %% 数据存储
    VectorDB --> Search
    Metadata[(MySQL元数据)] --> Search
    Cache --> |命中|CacheDB[(缓存数据库)]
    CacheDB[(缓存数据库)]--> Answer
    
    %% 样式
    classDef user fill:#e3f2fd
    classDef service fill:#f3e5f5
    classDef process fill:#e8f5e8
    classDef data fill:#fff3e0
    classDef ai fill:#ffebee
    
    class User,API user
    class Upload,Chat,Processor,Splitter,Embedding,Search,Rerank service
    class Cache,Answer process
    class VectorDB,Metadata,CacheDB data
    class LLM ai
```

## 🚀 核心功能

### 📁 文件上传处理流程

```mermaid
flowchart TD
    A[用户上传文件] --> B[KnowledgeBaseController upload]
    B --> C[KnowledgeBaseService 处理文件]
    C --> D[读取原始文件内容]
    D --> E[文本预处理]
    E --> F[滑动窗口文本切分]
    F --> G[按段落和句子生成文本块]
    G --> H[调用 Embedding 生成向量]
    H --> I[写入 Milvus 向量库]
    I --> J[元数据存入 MySQL]
    J --> K[返回 documentId]
```

### 💬 智能问答流程

```mermaid
flowchart TD
    A[用户查询] --> B[ChatController]
    B --> C[ChatOrchestrationService]
    C --> D{检查精确缓存?}
    D -- 命中 --> E[返回精确答案] --> U[返回回答]
    D -- 未命中 --> F{检查语义答案缓存?}
    F -- 命中 --> G[返回语义答案] --> R[缓存结果]
    F -- 未命中 --> H[意图识别]

    H --> I{意图/相似度判断}
    I -- 高 --> J[RAG服务]
    I -- 低 --> K[日常聊天服务]
    I -- 中等 --> L[自适应回答服务]

    J --> M{检查语义文档缓存?}
    M -- 命中 --> N[使用缓存上下文生成答案] --> R
    M -- 未命中 --> O[执行完整RAG流程]
    O --> P[知识检索 → 构建消息 → 调用模型]
    P --> Q[生成回答] --> R

    K --> S[日常聊天回答] --> R
    L --> T[自适应回答] --> R

    R --> U
```

## 🛠️ 技术栈

### 后端框架
- **Spring Boot 3.x** - 应用框架
- **Reactor** - 响应式编程
- **Spring AI** - AI应用集成

### 数据存储
- **Milvus** - 向量数据库
- **MySQL** - 关系型数据库
- **Redis** - 缓存 & 向量检索

### AI服务
- **通义千问** - 大语言模型
- **阿里云Embedding** - 文本向量化
- **OpenSearch** - 重排序服务

### 文档处理
- **多格式支持** - PDF、MD等
- **滑动窗口分块** - 智能文本分割
- **语义分块** - 保持上下文连贯性

## 📚 API文档

### 文件上传接口

**接口：**

```http
POST /api/knowledge/upload
Content-Type: multipart/form-data
```

**参数:**
```
- file: 文件 (必填)
- fileName: 文件名 (必填)
- description: 文件描述 (可选)
```

**请求示例：**
```bash
curl -X POST \\
http://localhost:8080/api/knowledge/upload \\
-F "file=@document.pdf" \\
-F "fileName=技术文档" \\
-F "description=产品技术规格说明"
```

**响应示例：**
```json
{
  "success": true,
  "message": "文件上传处理成功",
  "data": "DOC_1701234567890_abc123def"
}
```

### 智能问答接口

**接口：**
```http
GET /api/chat/rag/stream
Accept: text/event-stream
```

**参数：**
```
- question: 问题内容 (必填)
```

**请求示例：**
```bash
curl -X GET \\
"http://localhost:8080/api/chat/rag/stream?question=什么是RAG架构?" \\
-H "Accept: text/event-stream"
```

**流式响应示例：**
```
data: {"content": "RAG", "finished": false}

data: {"content": "架构是", "finished": false}

data: {"content": "检索增强生成...", "finished": true, "usage": {"inputTokens": 150, "outputTokens": 89, "totalTokens": 239}}
```

### 知识检索接口

**接口：**
```http
GET /api/knowledge/search
```
**参数：**
```
- query: 查询内容 (必填)
- topK: 返回数量 (可选，默认10)
- topRatio: 截断比例 (可选)
```

4. **在线访问服务**
```
http://115.190.202.146:3000/
# 运行需要成本，非7*24小时运行，如有需要可以通过邮箱联系我
```

## 🤝 贡献指南

我们欢迎任何形式的贡献！请阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 了解如何参与项目开发。

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 📞 联系我们

- 项目主页：https://github.com/Yuri-hack/rag-knowledge-qa
- 问题反馈：https://github.com/Yuri-hack/rag-knowledge-qa/issues
- 邮箱：15690863316@163.com

---

**如果这个项目对您有帮助，请给个⭐️支持一下！**