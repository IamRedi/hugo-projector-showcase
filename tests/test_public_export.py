import unittest
from tools.check_public_export import forbidden_path, text_issues, png_has_metadata

class PublicationPolicyTests(unittest.TestCase):
    def test_private_files_rejected(self):
        for name in ['android/local.properties', '.private/tokens.json', 'uploads/example.jpg', 'backup.tar', 'debug.apk', '.env.production', 'signing.jks']:
            with self.subTest(name=name):
                self.assertTrue(forbidden_path(name))

    def test_source_and_wrapper_allowed(self):
        for name in ['android/app/src/main/java/com/hugo/projector/TokenVault.kt', 'android/gradle/wrapper/gradle-wrapper.jar', 'README.md']:
            self.assertFalse(forbidden_path(name))

    def test_credentials_rejected(self):
        self.assertTrue(text_issues('val token = "synthetic_fixture_value_1234"'))
        self.assertTrue(text_issues('-----BEGIN PRIVATE KEY-----'))

    def test_private_links_paths_and_network_rejected(self):
        for value in ['C:/Users/Example/private', 'https://docs.google.com/document/d/synthetic/edit', 'http://192.168.5.8:39090/', 'http://10.2.3.4/', 'http://172.20.1.8/']:
            self.assertTrue(text_issues(value))

    def test_dynamic_auth_loopback_and_versions_allowed(self):
        for value in ['val token = randomToken()', "let token = '';", 'http://127.0.0.1:39090/media/', 'Gradle 8.10.2, ZXing 3.5.3']:
            self.assertEqual(text_issues(value), [])

    def test_png_metadata_rejected(self):
        signature = b'\x89PNG\r\n\x1a\n'
        self.assertTrue(png_has_metadata(signature + b'\x00\x00\x00\x00tEXt' + b'\0'*4))
        self.assertFalse(png_has_metadata(signature + b'\x00\x00\x00\x00IEND' + b'\0'*4))

if __name__ == '__main__':
    unittest.main()
