import { Suspense } from 'react';
import { ScannerScreen } from '@/features/scanner/ScannerScreen';

/** The filters of S3 live in the address, read with useSearchParams (inside a Suspense boundary). */
export default function ScannerPage() {
  return (
    <Suspense fallback={null}>
      <ScannerScreen />
    </Suspense>
  );
}
