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
		- ORDER_STATUS: 特定の注文の配送状況・追跡について尋ねている
		- CHANGE_ADDRESS: 配送先住所などの変更を依頼している
		- REFUND: 特定の注文について返金の **動作** を要求・依頼している（"返金してほしい" "返金してください" "払い戻しを" 等の動作要求）
		- QUESTION: 返品/返金/配送などのポリシー・手順・所要日数・タイミングを尋ねている（"返品ポリシーは?" "返金にどのくらいかかる?" "返金はいつ届く?" 等の情報問い合わせ）
		- OTHER: 上記いずれにも該当しない問い合わせ

		判別の指針:
		- 字面が似ている "返品" と "返金" は同じくくりにせず、ポリシーや所要日数を聞くのは QUESTION、特定の注文について動作を要求するのが REFUND
		- 「いつ届くか」「どのくらいかかるか」のように状況・タイミングを尋ねるのは（注文 ID 指定があっても）QUESTION 寄り。明示的に動作を要求する語（"〜してください" "〜してほしい"）がある場合のみ REFUND
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
