package com.example.csdemo

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Step 2-1：LLM に分類させるための構造化出力の型。
 */
@Serializable
@SerialName("SupportIntent")
enum class SupportIntent {
	ORDER_STATUS,
	CANCEL_ORDER,
	CHANGE_ADDRESS,
	REFUND,
	QUESTION,
	OTHER,
}

@Serializable
@LLMDescription("お客様のメッセージから抽出した、正規化されたサポート問い合わせ。")
data class SupportRequest(
	@property:LLMDescription(
		"""
		検出した問い合わせの種別。次の基準で 1 つだけ選ぶこと:
		- ORDER_STATUS: **特定の注文 ID** の配送状況・追跡・ステータスについて尋ねている
		  （"注文 XXX の配送状況" "注文 XXX はいつ届く?" "注文 XXX のステータス" "注文 XXX は今どこ?" 等）
		- CANCEL_ORDER: 特定の注文のキャンセル・取り消し・中止を希望している（"キャンセル" "取り消し" "中止" "やめたい" 等の動作）
		- CHANGE_ADDRESS: 配送先住所などの変更を依頼している
		- REFUND: 特定の注文について返金の **動作** を要求・依頼している（"返金してほしい" "返金してください" "払い戻しを" 等の動作要求）
		- QUESTION: 返品/返金/配送などのポリシー・手順・所要日数・タイミングを **一般論として** 尋ねている
		  （"返品ポリシーは?" "通常配送はどのくらい?" "返金にどのくらいかかる?" "配送日数の目安" 等の情報問い合わせ、注文 ID なし）
		- OTHER: 上記いずれにも該当しない問い合わせ

		判別の指針:
		- 字面が似ている "返品" と "返金" は同じくくりにせず、ポリシーや所要日数を聞くのは QUESTION、特定の注文について動作を要求するのが REFUND
		- "キャンセル" "取り消し" は REFUND でも ORDER_STATUS でも QUESTION でもなく **CANCEL_ORDER**。返金が伴うかどうかは別問題でこの段階では考慮しない
		- **ORDER_STATUS vs QUESTION の境界**: 注文 ID がある + 「配送状況」「いつ届く」「ステータス」を聞くのは ORDER_STATUS。注文 ID なし + 一般的な配送目安/ポリシーを聞くのは QUESTION。「注文 84721 の配送状況」のように ID 付きでステータスを聞くのは、ポリシーではなく ORDER_STATUS
		- 「いつ届くか」「どのくらいかかるか」のように状況・タイミングを尋ねるとき、**注文 ID があれば ORDER_STATUS**、なければ QUESTION
		- 明示的に動作を要求する語（"〜してください" "〜してほしい"）がある場合のみ REFUND
		""",
	)
	val intent: SupportIntent,

	@property:LLMDescription("お客様が言及した注文 ID。言及がなければ null")
	val orderId: String? = null,

	@property:LLMDescription(
		"""
		お客様の要望を 1 文で要約したもの。**お客様が実際に発話した内容の範囲だけ**を要約し、
		入力にない手続き・条件・周辺事情（メールアドレスの照合、返金手続、発送状況の確認 など）を
		補完して書かない。動詞 + 目的を最小限の語数で。30 字以内が目安。
		例: "注文 ABC9 のキャンセルを希望" / "返品ポリシーの確認" / "配送状況の問い合わせ"
		""",
	)
	val summary: String,
)
