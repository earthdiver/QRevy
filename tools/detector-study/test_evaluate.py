"""Metric checks; no native library scores or implementation details are hard-coded."""
import unittest
from evaluate import geometry_error

class GeometryMetricTest(unittest.TestCase):
    def test_order_does_not_change_physical_localization(self):
        q=[[10,10],[157,10],[157,157],[10,157]]
        truth={'quad':q,'n':21}
        for k in range(4): self.assertLess(geometry_error(q[k:]+q[:k],truth),1e-6)

    def test_translation_is_measured_in_modules(self):
        q=[[10,10],[157,10],[157,157],[10,157]]
        truth={'quad':q,'n':21}
        self.assertAlmostEqual(geometry_error([[x+7,y] for x,y in q],truth),1,places=5)
        self.assertGreater(geometry_error([[x+4,y] for x,y in q],truth),.5)

    def test_wrong_shape_is_rejected(self):
        self.assertEqual(geometry_error([[0,0]],{'quad':[[0,0],[21,0],[21,21],[0,21]],'n':21}),float('inf'))

if __name__=='__main__': unittest.main()
