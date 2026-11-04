import json
import unittest
from adb_redacted_tree import sanitize


class RedactionTest(unittest.TestCase):
    def test_text_is_replaced_and_duplicate_observations_remain(self):
        result = sanitize('<hierarchy><node package="jp.naver.line.android" class="android.widget.TextView" text="PRIVATE"/><node package="jp.naver.line.android" class="android.widget.TextView" text="PRIVATE"/></hierarchy>')
        self.assertEqual(2, len(result["nodes"]))
        self.assertEqual(["TEXT_001", "TEXT_001"], [n["text"] for n in result["nodes"]])
        self.assertNotIn("PRIVATE", json.dumps(result))

    def test_password_input_and_foreign_subtrees_are_pruned(self):
        for attributes in ['package="bank.app"', 'package="jp.naver.line.android" password="true"', 'package="jp.naver.line.android" class="android.widget.EditText"']:
            result = sanitize(f'<hierarchy><node {attributes} text="SECRET"><node package="jp.naver.line.android" text="DRAFT"/></node></hierarchy>')
            self.assertEqual([], result["nodes"])
            self.assertNotIn("SECRET", json.dumps(result))
            self.assertNotIn("DRAFT", json.dumps(result))

    def test_bounded_and_structural_fields_cannot_carry_text(self):
        result = sanitize('<hierarchy>' + '<node package="jp.naver.line.android" class="private field" resource-id="private data" bounds="private data"/>' * 450 + '</hierarchy>')
        self.assertEqual(400, len(result["nodes"]))
        self.assertTrue(result["truncated"])
        self.assertNotIn("private", json.dumps(result))

    def test_control_only_accepts_our_debug_app_and_does_not_expand_scope(self):
        xml = '<hierarchy><node package="dev.linguabridge.debug" class="android.widget.TextView" text="CONTROL"/></hierarchy>'
        self.assertEqual([], sanitize(xml)["nodes"])
        result = sanitize(xml, "dev.linguabridge.debug")
        self.assertEqual("TEXT_001", result["nodes"][0]["text"])
        self.assertNotIn("CONTROL", json.dumps(result))
        with self.assertRaises(ValueError):
            sanitize(xml, "bank.app")

    def test_wechat_is_not_an_allowed_target_anymore(self):
        xml = '<hierarchy><node package="com.tencent.mm" text="PRIVATE_OLD_TARGET"/></hierarchy>'
        result = sanitize(xml)
        self.assertEqual([], result["nodes"])
        self.assertNotIn("PRIVATE_OLD_TARGET", json.dumps(result))
        with self.assertRaises(ValueError):
            sanitize(xml, "com.tencent.mm")


if __name__ == "__main__":
    unittest.main()
