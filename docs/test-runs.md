1. No-MRC, 1 thread:

```
(base) mgu@z30b:~/../TrulyFreeOCR$ ./deps/jdk/bin/java -jar build/trulyfreeocr.jar --settings settings.jsonc --threads 1 --no-mrc ./tests/eval-corpus/sherlock-holmes-010p.pdf

TrulyFreeOCR v1.0.0
  Input:  ./tests/eval-corpus/sherlock-holmes-010p.pdf (1.9 MB)
  Output: output.pdf
  DPI:    300.0
  PSM:    1
  MRC:    off
  Engine: tesseract
  OCR Workers: 1 thread(s)
  Pages:  10
  Processing 10 pages...
    [ocr-1] page 1:  8.8s [+ 9.4s to walltime]
    [ocr-1] page 2: 11.6s [+21.0s to walltime]
    [ocr-1] page 3: 10.4s [+31.4s to walltime]
    [ocr-1] page 4:  9.4s [+40.8s to walltime]
    [ocr-1] page 5: 10.0s [+50.8s to walltime]
    [ocr-1] page 6:  9.7s [+60.5s to walltime]
    [ocr-1] page 7:  9.5s [+70.0s to walltime]
    [ocr-1] page 8:  7.5s [+77.5s to walltime]
    [ocr-1] page 9:  8.9s [+86.4s to walltime]
    [ocr-1] page 10: 10.2s [+96.6s to walltime]
    Processing done: 10 pages in 96.6s (1 threads)
  Writing text output...
  Assembling PDF...
  Finalizing document...
  Total: 10 pages in 1:41
  Output: output.pdf (7.3 MB)
  Words:  4292 (OCR)
Done.  prep+ocr 96.6s / asm 4.8s = 101.4s total
```

2. No-MRC, 2 threads:

```
(base) mgu@z30b:~/../TrulyFreeOCR$ ./deps/jdk/bin/java -jar build/trulyfreeocr.jar --settings settings.jsonc --threads 2 --no-mrc ./tests/eval-corpus/sherlock-holmes-010p.pdf

TrulyFreeOCR v1.0.0
  Input:  ./tests/eval-corpus/sherlock-holmes-010p.pdf (1.9 MB)
  Output: output.pdf
  DPI:    300.0
  PSM:    1
  MRC:    off
  Engine: tesseract
  OCR Workers: 2 thread(s)
  Pages:  10
  Processing 10 pages...
    [ocr-1] page 1:  9.8s [+10.8s to walltime]
    [ocr-2] page 2: 13.2s [+14.5s to walltime]
    [ocr-1] page 3: 11.7s [+22.6s to walltime]
    [ocr-2] page 4: 10.4s [+25.0s to walltime]
    [ocr-1] page 5: 11.0s [+33.5s to walltime]
    [ocr-2] page 6: 11.1s [+36.0s to walltime]
    [ocr-2] page 8:  8.1s [+44.2s to walltime]
    [ocr-1] page 7: 11.0s [+44.6s to walltime]
    [ocr-2] page 9:  9.8s [+53.9s to walltime]
    [ocr-1] page 10: 11.3s [+55.9s to walltime]
    Processing done: 10 pages in 55.9s (2 threads)
  Writing text output...
  Assembling PDF...
  Finalizing document...
  Total: 10 pages in 1:00
  Output: output.pdf (7.3 MB)
  Words:  4292 (OCR)
Done.  prep+ocr 55.9s / asm 4.5s = 60.4s total
```

3. MRC, 1 thread:

```
(base) mgu@z30b:~/../TrulyFreeOCR$ ./deps/jdk/bin/java -jar build/trulyfreeocr.jar --settings settings.jsonc --threads 1 ./tests/eval-corpus/sherlock-holmes-010p.pdf

TrulyFreeOCR v1.0.0
  Input:  ./tests/eval-corpus/sherlock-holmes-010p.pdf (1.9 MB)
  Output: output.pdf
  DPI:    300.0
  PSM:    1
  MRC:    on
  Engine: tesseract
  OCR Workers: 1 thread(s)
  Pages:  10
  Processing 10 pages...
    [ocr-1] page 1: 11.7s [+12.4s to walltime]
    [ocr-1] page 2: 14.0s [+26.3s to walltime]
    [ocr-1] page 3: 12.6s [+38.9s to walltime]
    [ocr-1] page 4: 11.6s [+50.5s to walltime]
    [ocr-1] page 5: 11.8s [+62.3s to walltime]
    [ocr-1] page 6: 11.8s [+74.0s to walltime]
    [ocr-1] page 7: 11.1s [+85.2s to walltime]
    [ocr-1] page 8:  9.4s [+94.6s to walltime]
    [ocr-1] page 9: 10.4s [+104.9s to walltime]
    [ocr-1] page 10: 12.1s [+117.0s to walltime]
    Processing done: 10 pages in 117.0s (1 threads)
  Writing text output...
  Batch JBIG2 compression...
    JBIG2 batch done in 0.7s (sym: 2533 bytes)
  Assembling PDF...
  Finalizing document...
  Total: 10 pages in 2:36
  Output: output.pdf (474 KB)
  Words:  4292 (OCR)
Done.  prep+ocr 117.0s / asm 39.4s = 156.4s total
```

4. MRC, 2 threads:

```
(base) mgu@z30b:~/../TrulyFreeOCR$ ./deps/jdk/bin/java -jar
build/trulyfreeocr.jar --settings settings.jsonc --thre
ads 2 ./tests/eval-corpus/sherlock-holmes-010p.pdf

TrulyFreeOCR v1.0.0
  Input:  ./tests/eval-corpus/sherlock-holmes-010p.pdf (1.9 MB)
  Output: output.pdf
  DPI:    300.0
  PSM:    1
  MRC:    on
  Engine: tesseract
  OCR Workers: 2 thread(s)
  Pages:  10
  Processing 10 pages...
    [ocr-1] page 1: 13.9s [+14.9s to walltime]
    [ocr-2] page 2: 17.5s [+18.8s to walltime]
    [ocr-1] page 3: 14.2s [+29.0s to walltime]
    [ocr-2] page 4: 12.0s [+30.7s to walltime]
    [ocr-1] page 5: 13.5s [+42.5s to walltime]
    [ocr-2] page 6: 13.6s [+44.3s to walltime]
    [ocr-2] page 8: 11.2s [+55.5s to walltime]
    [ocr-1] page 7: 14.2s [+56.7s to walltime]
    [ocr-2] page 9: 12.4s [+68.0s to walltime]
    [ocr-1] page 10: 13.8s [+70.5s to walltime]
    Processing done: 10 pages in 70.5s (2 threads)
  Writing text output...
  Batch JBIG2 compression...
    JBIG2 batch done in 0.7s (sym: 2533 bytes)
  Assembling PDF...
  Finalizing document...
  Total: 10 pages in 1:50
  Output: output.pdf (474 KB)
  Words:  4292 (OCR)
Done.  prep+ocr 70.5s / asm 39.6s = 110.1s total
```

5. PaddleOCR engine (WIP — placeholder for actual run output):

```
# Example: ./deps/jdk/bin/java -jar build/trulyfreeocr.jar --ocr-engine paddle ./tests/eval-corpus/sherlock-holmes-010p.pdf
# Expected output would include "Engine: paddle" in the console header.
```
