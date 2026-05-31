# koog-spring-ai-cs-demo

Koog 0.8.0 + Spring AI 1.1.x の統合（[JetBrains 2026/04 のブログ記事](https://blog.jetbrains.com/ai/2026/04/koog-meets-spring-ai/)）を題材にした学習用カスタマーサポート bot。1 commit ごとに 1 学習ステップを積み上げる形で構築した。

## 動かし方

1. pgvector 拡張入りの Postgres を起動:

```bash
docker compose up -d
```

2. OPENAI_API_KEY を環境変数に設定して bootRun:

```bash
OPENAI_API_KEY=sk-... ./gradlew bootRun
```

起動時に `PgVectorStore` が schema を作成し、空であれば FAQ を seed する（`text-embedding-3-small` で embed されるため有効な API key が必須）。**volume にデータが残るので 2 回目以降の起動では再 seed されない**（`docker compose down -v` で完全リセット可能）。

## API エンドポイント

### `POST /chat` — Step 1：LLM 最小疎通

`PromptExecutor` 経由で 1 往復だけ問い合わせる。Koog の最小構成（`singleRunStrategy()` + `nodeLLMRequest`）。

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "こんにちは"}'
```

### `POST /support` — Step 2-1：構造化分類

ユーザ発話を `SupportRequest`（intent / orderId / summary）に分類して JSON で返す。`nodeLLMRequestStructured` + `@Serializable` + `@LLMDescription` で schema 強制。

```bash
curl -X POST http://localhost:8080/support \
  -H "Content-Type: application/json" \
  -d '{"prompt": "注文 12345 はどこですか"}'
# -> {"intent":"ORDER_STATUS","orderId":"12345","summary":"..."}
```

### `POST /support/handle` — Step 2-2 以降：ルーティング + RAG + ChatMemory

classify -> intent 別に分岐して回答する。`sessionId` 単位で会話履歴を保持し、QUESTION 系の経路では FAQ VectorStore を grounding する。

```bash
curl -X POST http://localhost:8080/support/handle \
  -H "Content-Type: application/json" \
  -d '{"prompt": "返品ポリシーを教えてください", "sessionId": "demo"}'
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
                                 └─ AIAgent + answerStrategy() + ANSWER_PROMPT
                                       ├─ toolRegistry = { tools(OrderTools()) }  -- cancelOrder 等の action tool
                                       ├─ install(ChatMemory.Feature) {
                                       │      chatHistoryProvider(historyProvider)
                                       │      addPreProcessor(StripFaqContextPreProcessor())  -- store 直前で FAQ block を剥がす
                                       │  }
                                       └─ handleEvents { onToolCallStarting { ... } }  -- tool 呼びを構造化ログ
```

`answerStrategy()` の中身 (singleRunStrategy() を inline 展開 + history compression):

```
Start -> callLLM ──onAssistantMessage→ Finish (plain text)
            │
            onToolCall
            ↓
          executeTool ──onCondition (messages > THRESHOLD)→ compressHistory ─→ sendToolResult
                      └──(それ以外)──────────────────────────────────────────→ sendToolResult
                                                                                    │
                                                                             onAssistantMessage→ Finish
                                                                                    │
                                                                             onToolCall→ executeTool (ループ)
```

- **VectorStore**: pgvector 拡張入り Postgres を Spring AI の `PgVectorStore` で叩く。起動時に schema 作成 + FAQ 6 件 seed（空のときのみ）。`koog-spring-ai-starter-vector-store` の bridge が `VectorStore` Bean を `KoogVectorStore` として wrap し、Koog の `SearchStorage<TextDocument, SimilaritySearchRequest>` 経由で叩ける。実装は Step 4-1 で in-memory な `SimpleVectorStore` から始まり、後段で PgVector に Bean 差し替えのみで移行している
- **ChatHistoryProvider**: Spring AI `InMemoryChatMemoryRepository` を `koog-spring-ai-starter-chat-memory` bridge 経由で Koog の `ChatHistoryProvider` として登録
- **PromptExecutor**: `koog-spring-ai-starter-model-chat` が Spring AI の `ChatModel` を Koog の `PromptExecutor` に橋渡し
- **Tool calling**: `OrderTools : ToolSet` の `@Tool` 付きメソッドをリフレクションで `ToolRegistry` に登録。`answerStrategy()` は `singleRunStrategy()` と同じ tool ループ構造を自前で展開しつつ、`executeTool` の後段に `nodeLLMCompressHistory` を条件付き edge で挟んで、長い会話で履歴を要約圧縮できるようにしている。`HistoryCompressionStrategy.FromLastNMessages(N)` で直近 N 件以外を summary に置換

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
| **(jp 化)** | プロンプト / FAQ seed / `@LLMDescription` / curl サンプルを日本語化。embedding スコア分布が変わり、英語で取れなかった refund query が日本語だと拾えるという副次効果あり |
| **(classifier 精度)** | `@LLMDescription` に per-intent ルールを書き込み、examples に QUESTION 系の追加例を入れて REFUND vs QUESTION の境界を明示。8/8 期待通りの判定に |
| **(任意) PgVector 化** | `docker-compose.yml` + `spring-ai-starter-vector-store-pgvector` を入れて、Bean 差し替えだけで infra を pgvector に切り替え。schema 初期化が Spring lifecycle で走るので、seed は `@Bean` factory から `ApplicationRunner` に移動 |
| **(tool calling)** | `OrderTools : ToolSet` + `@Tool cancelOrder` を新規追加。`answerWithFaqGrounding` の `ToolRegistry` を `EMPTY` から OrderTools 登録に切り替え、`singleRunStrategy()` の内蔵 tool ループに乗せる。`handleEvents { onToolCallStarting }` で tool 呼びを構造化ログ。classifier examples に cancel 例を追加し `ORDER_STATUS` への誤分類を抑止。`ANSWER_PROMPT` に「操作系 tool 利用可、明示依頼は即実行」段落を追加（これがないと LLM は確認待ちに流れて tool を呼ばない） |
| **(strategy inline + history compression)** | `singleRunStrategy()` を捨てて、`answerStrategy()` という同等の自前 strategy を `strategy<String, String>(...) { ... }` で inline 展開。`executeTool` の後段に `nodeLLMCompressHistory<ReceivedToolResult>` を `onCondition { messages.size > THRESHOLD }` 付き edge で挟み、長セッションで履歴を `FromLastNMessages(N)` で要約圧縮。**実測で観察された副作用**: compress 後に LLM が過去処理済みの注文 ID を再キャンセルしようとする (context 損失) — 詳細は所感セクション 5 |

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

## Tool calling 周りで実測した所感

### 1. `chatAgentStrategy()` は tool 呼びを強制する設計で、混在 demo にハマる

`chatAgentStrategy()` は内部に `giveFeedbackToCallTools` という補助ノードを持ち、**LLM が tool を 1 度も呼ばずに plain text で答えようとすると「tool 使え」と feedback を投げ返す**。tool で答えるべきタスク（計算機など）だけで構成された agent なら自然に終了するが、`/support/handle` のように「キャンセル系は tool で、FAQ 系は plain text で」混在する用途では、FAQ 質問が `AIAgentMaxNumberOfIterationsReachedException`（50 iter 上限）で死ぬ。

ワークアラウンドとして `SayToUser` built-in tool を `ToolRegistry` に同居させると、LLM は「plain text で答える」代わりに「`SayToUser({"message": "..."})` を呼ぶ」ことで feedback ループを抜けられる（Calculator example の registry に `tool(SayToUser)` が入っていたのはこのため）。ただし **`SayToUser` の実装は `Agent says: ...` を println するだけで、HTTP response には乗らない** — Koog の REPL / コンソールアプリ前提の built-in。HTTP server には不向き。

### 2. `singleRunStrategy()` の中身がそのまま「tool ループ + plain text 直返し」を実装している

docs にある singleRunStrategy の参考実装:

```kotlin
strategy("single_run") {
    val nodeCallLLM by nodeLLMRequest()
    val nodeExecuteTool by nodeExecuteTool()
    val nodeSendToolResult by nodeLLMSendToolResult()

    edge(nodeStart forwardTo nodeCallLLM)
    edge(nodeCallLLM forwardTo nodeExecuteTool onToolCall { true })
    edge(nodeCallLLM forwardTo nodeFinish onAssistantMessage { true })
    edge(nodeExecuteTool forwardTo nodeSendToolResult)
    edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
    edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
}
```

→ tool 呼ばれたら実行 + 結果を LLM に戻して再 LLM、tool 呼ばれなかったら plain text を `nodeFinish` に流す、というシンプルなループ。`chatAgentStrategy()` の tool 強制とは違って、tool を **呼んでも呼ばなくてもよい**。今回の demo の用途にはこちらが素直で、結果として `ToolRegistry { tools(OrderTools()) }` を渡すだけで tool 経路が動く。

### 3. LLM が tool を呼ぶかどうかは ANSWER_PROMPT 次第

`ToolRegistry` に登録するだけでは LLM は tool を必ず呼ぶわけではない。`ANSWER_PROMPT` が FAQ ground 専用の文面しか持っていなかった当初、GPT5Nano は「注文 ABC123 をキャンセルしたいです」に対して **tool を呼ばずに「キャンセルしますか？よろしいですか？」と確認待ち応答** に流れた（FAQ retrieval が同時に走るので、LLM は FAQ ベースの回答にも引っ張られていた）。

`ANSWER_PROMPT` の末尾に **「操作系のツール（例: 注文のキャンセル）が利用可能です。お客様が明示的に該当の操作を希望している場合は、追加で確認を取らずに直接ツールを呼び出してください。」** という 1 段落を追記したら、cancelOrder tool が安定して呼ばれるようになった。tool の存在を system prompt に書いておかないと、小さいモデルは自己判断で tool を呼ぶよりも自然言語で確認する方を選びがち。

### 4. `singleRunStrategy()` を自前展開して `nodeLLMCompressHistory` を挟む

公式提供の `singleRunStrategy()` をそのまま使う方針から一歩進めて、同じグラフ構造を `strategy<String, String>(...) { ... }` で inline 展開。`executeTool` の後段に `nodeLLMCompressHistory<ReceivedToolResult>` を `onCondition { ... }` 付き edge で挟み、tool 実行後に履歴が肥大していたら圧縮を経由してから `sendToolResult` に流れる形にした。

```kotlin
val executeTool by nodeExecuteTool()
val compressHistory by nodeLLMCompressHistory<ReceivedToolResult>(
    strategy = HistoryCompressionStrategy.FromLastNMessages(4),
    preserveMemory = true,
)
val sendToolResult by nodeLLMSendToolResult()

edge(executeTool forwardTo compressHistory onCondition { _ ->
    llm.readSession { prompt.messages.size } > THRESHOLD
})
edge(executeTool forwardTo sendToolResult)         // 上の edge にマッチしないとき
edge(compressHistory forwardTo sendToolResult)
```

ポイント:
- **edges are checked in the order they are defined** なので、条件付き edge を先に書き、fallback の無条件 edge を後に置く
- `onCondition` は `AIAgentEdgeBuilderIntermediate` のメソッド（top-level の `onToolCall`/`onAssistantMessage` とは違う）。import 不要で chain で呼べる
- lambda 内で `llm.readSession { ... }` で現 LLM session の prompt にアクセスできる

### 5. `FromLastNMessages` 圧縮後に LLM が context を見失う副作用

`HistoryCompressionStrategy.FromLastNMessages(4)` で同 session の cancel 系発話を 5-8 turn 連投すると、threshold (messages > 6) を超えたタイミングで compression が発火し、prompt.messages 数が圧縮後 6 程度に収まることを観察できた。

ただし圧縮後の turn で LLM の挙動に副作用が出た: **ユーザが ABC4 のキャンセルを依頼したのに、LLM が cancelOrder(ABC4) を呼んだ直後に cancelOrder(ABC2) も追加で呼ぶ** — 過去 turn で処理済みの ABC2 を「やり残し」と誤認した形。`FromLastNMessages` は直近 N 件以外を TLDR 形式で summary message に圧縮するため、その summary に固有名詞 (注文 ID) が残ると LLM が「未処理」と取って tool を再起動してしまう。

対処の方向性 (このリポジトリではまだ未実装):
- `ANSWER_PROMPT` に「過去 turn で処理済みの注文を再キャンセルしない」を明記
- `HistoryCompressionStrategy.WholeHistory` / `Chunked` / `NoCompression` 等の挙動と比較する
- そもそも `ChatMemory.Feature` がデフォルトで tool call / tool result message を persist しない (今回観察)、つまり「ChatMemory 経由の永続履歴」と「単 AIAgent 内の prompt history」を区別する設計が必要

## 触れていない領域 / 改善余地

- ストリーミング応答
- retrieval miss 時の hallucination 抑制（`ANSWER_PROMPT` に "FAQ がない場合は具体的な数字を出さずに案内に留める" 系の制約を追加）
- test 実行は引き続き OPENAI_API_KEY + 起動中の Postgres が必要（context load で seed が走るため）。CI 向けには `@MockBean` で `EmbeddingModel` を差し替える、もしくは `@Profile("!test")` で seeder を切るなど

## ディレクトリ構成（src 配下）

```
src/main/kotlin/com/example/csdemo/
  ├── KoogSpringAiCsDemoApplication.kt   -- Spring Boot エントリ
  ├── ChatController.kt                  -- /chat (Step 1)
  ├── SupportController.kt               -- /support, /support/handle (Step 2 以降)
  ├── SupportTypes.kt                    -- SupportRequest / SupportIntent
  ├── SupportGraphService.kt             -- classify / handle / answerWithFaqGrounding
  ├── OrderTools.kt                      -- ToolSet 実装、cancelOrder などの action tool
  ├── FaqAugment.kt                      -- FaqAugment.build + StripFaqContextPreProcessor
  ├── ChatMemoryConfig.kt                -- Spring AI ChatMemoryRepository Bean (Step 3-2)
  └── VectorStoreConfig.kt               -- SimpleVectorStore + seed FAQ (Step 4-1)
```
