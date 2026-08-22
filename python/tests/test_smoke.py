import unittest

from ps_license_sdk import LicenseClient, machine_code, sign


class SmokeTest(unittest.TestCase):
    def test_machine_code_stable(self):
        self.assertEqual(machine_code(), machine_code())
        self.assertGreaterEqual(len(machine_code()), 8)

    def test_sign_deterministic(self):
        params = {"productUniqueCode": "PRO-2026-001", "machineCode": "M123", "edition": "PRO", "expiryDays": 0, "clientOrderId": "x", "licenseCode": "", "timestamp": 1000}
        self.assertEqual(sign("secret", params), sign("secret", params))

    def test_purchase_url(self):
        url = LicenseClient(product_unique_code="PRO-2026-001").purchase_url("MABC")
        self.assertIn("productUniqueCode=PRO-2026-001", url)
        self.assertIn("machineCode=MABC", url)


if __name__ == "__main__":
    unittest.main()
