# koog-spring-ai-cs-demo

Koog 0.8.0 + Spring AI 1.1.x の統合（[JetBrains 2026/04 のブログ記事](https://blog.jetbrains.com/ai/2026/04/koog-meets-spring-ai/)）を題材にした学習用カスタマーサポート bot。1 commit ごとに 1 学習ステップを積み上げる形で構築した。

## 動かし方

OPENAI_API_KEY を環境変数に設定して bootRun:

```bash
OPENAI_API_KEY=sk-... ./gradlew bootRun
```

起動時に Spring AI の `SimpleVectorStore` Bean が seed FAQ を `text-embedding-3-small` で embed するため、有効な API key が必須（test 実行時も同様）。

## API エンドポイント

### `POST /chat` — Step 1：LLM 最小疎通

`PromptExecutor` 経由で 1 往復だけ問い合わせる。Koog の最小構成（`singleRunStrategy()` + `nodeLLMRequest`）。

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Hello"}'
```

### `POST /support` — Step 2-1：構造化分類

ユーザ発話を `SupportRequest`（intent / orderId / summary）に分類して JSON で返す。`nodeLLMRequestStructured` + `@Serializable` + `@LLMDescription` で schema 強制。

```bash
curl -X POST http://localhost:8080/support \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Where is my order 12345?"}'
# -> {"intent":"ORDER_STATUS","orderId":"12345","summary":"..."}
```

### `POST /support/handle` — Step 2-2 以降：ルーティング + RAG + ChatMemory

classify -> intent 別に分岐して回答する。`sessionId` 単位で会話履歴を保持し、QUESTION 系の経路では FAQ VectorStore を grounding する。

```bash
curl -X POST http://localhost:8080/support/handle \
  -H "Content-Type: application/json" \
  -d '{"prompt": "What is your return policy?", "sessionId": "demo"}'
```

## アーキテクチャ

```
POST /support/handle
  └─ SupportGraphService.handle(prompt, sessionId)
      ├─ classify(prompt)                       -- ChatMemory なしの classifier agent
      │    └─ AIAgent + classifyStrategy        -- nodeLLMRequestStructured で SupportRequest を返す
      │
      └─ when (intent)
           ├─ ORDER_STATUS -> orderStatusReply(request)            -- LLM 呼ばずに固定文
           └─ else         -> answerWithFaqGrounding(prompt, sessionId)
                                 ├─ augmentWithFaq(prompt)
                                 │     ├─ KoogVectorStore.search(SimilaritySearchRequest)
                                 │     └─ FaqAugment.build(query, faqs)  -- FAQ block を prepend
                                 │
                                 └─ AIAgent + singleRunStrategy + ANSWER_PROMPT
                                       └─ install(ChatMemory.Feature) {
                                              chatHistoryProvider(historyProvider)
                                              addPreProcessor(StripFaqContextPreProcessor())  -- store 直前で FAQ block を剥がす
                                          }
```

- **VectorStore**: in-memory `SimpleVectorStore`（Spring AI）に seed FAQ 6 件を投入。`koog-spring-ai-starter-vector-store` の bridge が `KoogVectorStore` Bean として wrap し、Koog の `SearchStorage<TextDocument, SimilaritySearchRequest>` 経由で叩ける
- **ChatHistoryProvider**: Spring AI `InMemoryChatMemoryRepository` を `koog-spring-ai-starter-chat-memory` bridge 経由で Koog の `ChatHistoryProvider` として登録
- **PromptExecutor**: `koog-spring-ai-starter-model-chat` が Spring AI の `ChatModel` を Koog の `PromptExecutor` に橋渡し

## Step ごとのハイライト

| Step | やったこと |
|---|---|
| **1** | `singleRunStrategy()` で LLM に 1 往復、`/chat` |
| **2-1** | `nodeLLMRequestStructured<SupportRequest>` で構造化分類、`/support` |
| **2-2** | `subgraph` + `edge onCondition` で intent 別ルーティング、`/support/handle` |
| **3-1** | Koog の `InMemoryChatHistoryProvider` を `ChatMemory.Feature` に install、`sessionId` で履歴分離 |
| **3-2** | `koog-spring-ai-starter-chat-memory` bridge を入れて、自前定義した Spring AI `ChatMemoryRepository` Bean に橋渡し |
| **3-3** | systemPrompt から分類指示を削る（schema 強制と examples で十分） |
| **4-1** | Spring AI 単独で `SimpleVectorStore` + 6 件 FAQ seed |
| **4-2** | `koog-spring-ai-starter-vector-store` bridge を入れて `LongTermMemory.Feature` で auto retrieval |
| **4-3** | `LongTermMemory.Feature` を外して、graph 内の `retrieveContext` ノードで手動 retrieval |
| **4-4a** | classifier と answer を別 agent に分離、Kotlin の `when` でルーティング |
| **4-4b** | minScore チューニング + `ANSWER_PROMPT` 強化 + retrieval score の構造化ログ |
| **4-4c** | `StripFaqContextPreProcessor` で ChatMemory に store される user message から FAQ block を剥がす |
| **(refactor)** | `FaqAugment` object に format を集約、test を `FaqAugmentTest` に切り出し、README 整備 |

## Step 4 で実測した RAG 周りの所感

### 1. embedding-based retrieval は表現の揺れに弱い

seed FAQ `"Refunds are processed within 5-7 business days after we receive your returned item."` に対して、query `"How long until I get my refund?"` が **minScore=0.5 でもヒットしない**（cosine similarity が 0.5 未満になる）。`text-embedding-3-small` が "are processed within" と "How long until" を意味的に同じと判定しない、というのが理由。

対処案（このリポジトリでは実装してない）:
- doc を query 形に寄せた表現で複数バリエーション seed する
- HyDE（query から仮想回答を生成 → それを embed → 検索）
- BM25 等の lexical search との hybrid

### 2. ChatMemory に augmented prompt が persistence されると次ターンが汚染される

`agent.run(augmentedPrompt, sessionId)` で渡した文字列は、Koog の `ChatMemory.Feature` によって **user role の Message としてそのまま store される**。FAQ block を prepend してから渡すと、次ターンの履歴で FAQ snippet が user 発言として replay される。LLM が "the FAQ you shared..." といった replay 由来の誤参照をするので、Step 4-4c で `StripFaqContextPreProcessor` を入れて store 直前に FAQ block を剥がすようにした。

`ChatMemoryPreProcessor` は `(List<Message>) -> List<Message>` で load/store 両方に効くフック。filter じゃなく transform できるので、message の content を書き換える用途に使える。

### 3. feature install は agent-scope

`ChatMemory.Feature` を install すると、その agent の graph 内すべての LLM ノードに効く。Step 4-3 までは routing graph 内に in-graph classifier (`nodeLLMRequestStructured`) を置いていたため、ChatMemory が classifier にも作用して `sessionId="demo"` で order の会話を積んだ後の `"Hello, how are you today?"` が ORDER_STATUS に誤分類される現象が観察できた。

→ Step 4-4a で classifier を別 AIAgent に切り出し、Kotlin の `when` で routing するようにして解消。「feature scope を分けたいなら agent を分ける」が正攻法。

## 触れていない領域 / 改善余地

- Step 4-4 の任意ステップ：`SimpleVectorStore` -> PGvector への置き換え（Docker Compose 追加、Bean 差し替えだけで動くことの確認）
- Tool calling / function calling
- ストリーミング応答
- retrieval miss 時の hallucination 抑制（`ANSWER_PROMPT` に "FAQ がない場合は具体的な数字を出さずに案内に留める" 系の制約を追加）

## ディレクトリ構成（src 配下）

```
src/main/kotlin/com/example/csdemo/
  ├── KoogSpringAiCsDemoApplication.kt   -- Spring Boot エントリ
  ├── ChatController.kt                  -- /chat (Step 1)
  ├── SupportController.kt               -- /support, /support/handle (Step 2 以降)
  ├── SupportTypes.kt                    -- SupportRequest / SupportIntent
  ├── SupportGraphService.kt             -- classify / handle / answerWithFaqGrounding
  ├── FaqAugment.kt                      -- FaqAugment.build + StripFaqContextPreProcessor
  ├── ChatMemoryConfig.kt                -- Spring AI ChatMemoryRepository Bean (Step 3-2)
  └── VectorStoreConfig.kt               -- SimpleVectorStore + seed FAQ (Step 4-1)
```
