package com.example.csdemo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportRoutingTest {

    @Test
    fun `注文キーワード + ID 風文字列の同居で true`() {
        assertTrue(looksLikeOrderOperation("注文 84721 の配送状況を教えてください"))
        assertTrue(looksLikeOrderOperation("注文 ABC123 をキャンセルしてください"))
        assertTrue(looksLikeOrderOperation("オーダー XYZ001 のキャンセル"))
        assertTrue(looksLikeOrderOperation("Please cancel order ABC9"))
    }

    @Test
    fun `ID なしの注文系質問は false（一般的な質問として FAQ 経路に流したい）`() {
        assertFalse(looksLikeOrderOperation("注文の流れを教えてください"))
        assertFalse(looksLikeOrderOperation("オーダーのキャンセル方法は？"))
        assertFalse(looksLikeOrderOperation("注文の確認方法を知りたい"))
    }

    @Test
    fun `注文キーワードなしは false（純粋な FAQ 質問）`() {
        assertFalse(looksLikeOrderOperation("ABC123 について教えて"))
        assertFalse(looksLikeOrderOperation("通常配送はどのくらいかかりますか"))
        assertFalse(looksLikeOrderOperation("返品ポリシーを教えてください"))
    }

    @Test
    fun `短い数字 ID は false（誤検出回避、例 オーダーNo 12）`() {
        assertFalse(looksLikeOrderOperation("オーダーNo. 12 の確認"))
    }

    @Test
    fun `英大文字 + 数字混在の ID は短くてもマッチ`() {
        // ABC1 / ORD1 など A4/A5 で使った最小サイズもカバーする
        assertTrue(looksLikeOrderOperation("注文 ABC1 をキャンセル"))
        assertTrue(looksLikeOrderOperation("注文 ORD1 の状況"))
    }
}
