(base) bgu@z30b:~/../TrulyFreeOCR$ ./gradlew build

BUILD SUCCESSFUL in 13s
9 actionable tasks: 8 executed, 1 up-to-date
(base) bgu@z30b:~/../TrulyFreeOCR$
(base) bgu@z30b:~/../TrulyFreeOCR$
(base) bgu@z30b:~/../TrulyFreeOCR$ ./deps/jdk/bin/java -jar build/trulyfreeocr.jar --settings settings.jsonc --threads 4 ./tests/eval-corpus/sherlock-holmes-010p.pdf

TrulyFreeOCR v1.0.0
  Input:  ./tests/eval-corpus/sherlock-holmes-010p.pdf
  Output: output.pdf
  OCR Workers: 4 thread(s)
  Processing 10 pages...
    [ocr-1] page 1: 20.5s [+21.8s to walltime]
    [ocr-4] page 4: 21.8s [+23.9s to walltime]
    [ocr-3] page 3: 24.3s [+26.1s to walltime]
    [ocr-2] page 2: 25.4s [+26.9s to walltime]
    [ocr-1] page 5: 21.6s [+43.5s to walltime]
    [ocr-2] page 8: 17.0s [+43.9s to walltime]
    [ocr-4] page 6: 20.7s [+44.6s to walltime]
    [ocr-3] page 7: 19.3s [+45.5s to walltime]
    [ocr-1] page 9: 11.1s [+54.6s to walltime]
    [ocr-2] page 10: 11.8s [+55.8s to walltime]
    Processing done: 10 pages in 55.8s (4 threads)
  Writing text output...
  Batch JBIG2 compression...
    JBIG2 batch done in 0.8s (sym: 2533 bytes)
  Assembling PDF...
  Finalizing document...
  Total: 10 pages in 1:16
Done.  prep+ocr 55.8s / asm 20.4s = 76.1s total

(base) bgu@z30b:~/../TrulyFreeOCR$
(base) bgu@z30b:~/../TrulyFreeOCR$
(base) bgu@z30b:~/../TrulyFreeOCR$ ./deps/jdk/bin/java -jar build/trulyfreeocr.jar --settings settings.jsonc --threads 2 ./tests/eval-corpus/sherlock-holmes-010p.pdf

TrulyFreeOCR v1.0.0
  Input:  ./tests/eval-corpus/sherlock-holmes-010p.pdf
  Output: output.pdf
  OCR Workers: 2 thread(s)
  Processing 10 pages...
    [ocr-1] page 1: 12.4s [+13.2s to walltime]
    [ocr-2] page 2: 15.1s [+16.0s to walltime]
    [ocr-1] page 3: 12.4s [+25.6s to walltime]
    [ocr-2] page 4: 10.2s [+26.3s to walltime]
    [ocr-1] page 5: 12.2s [+37.7s to walltime]
    [ocr-2] page 6: 11.7s [+38.0s to walltime]
    [ocr-2] page 8:  9.0s [+47.0s to walltime]
    [ocr-1] page 7: 11.1s [+48.9s to walltime]
    [ocr-2] page 9: 10.1s [+57.1s to walltime]
    [ocr-1] page 10: 10.7s [+59.5s to walltime]
    Processing done: 10 pages in 59.5s (2 threads)
  Writing text output...
  Batch JBIG2 compression...
    JBIG2 batch done in 0.7s (sym: 2533 bytes)
  Assembling PDF...
  Finalizing document...
  Total: 10 pages in 1:19
Done.  prep+ocr 59.5s / asm 20.2s = 79.7s total

(base) bgu@z30b:~/../TrulyFreeOCR$
(base) bgu@z30b:~/../TrulyFreeOCR$ ./deps/jdk/bin/java -jar build/trulyfreeocr.jar --settings settings.jsonc --threads 1 ./tests/eval-corpus/sherlock-holmes-010p.pdf

TrulyFreeOCR v1.0.0
  Input:  ./tests/eval-corpus/sherlock-holmes-010p.pdf
  Output: output.pdf
  OCR Workers: 1 thread(s)
  Processing 10 pages...
    [ocr-1] page 1: 10.5s [+11.4s to walltime]
    [ocr-1] page 2: 12.2s [+23.6s to walltime]
    [ocr-1] page 3: 10.6s [+34.2s to walltime]
    [ocr-1] page 4:  9.9s [+44.1s to walltime]
    [ocr-1] page 5: 10.1s [+54.2s to walltime]
    [ocr-1] page 6: 10.0s [+64.2s to walltime]
    [ocr-1] page 7:  9.6s [+73.8s to walltime]
    [ocr-1] page 8:  8.1s [+81.9s to walltime]
    [ocr-1] page 9:  9.1s [+91.0s to walltime]
    [ocr-1] page 10: 10.1s [+101.2s to walltime]
    Processing done: 10 pages in 101.2s (1 threads)
  Writing text output...
  Batch JBIG2 compression...
    JBIG2 batch done in 0.9s (sym: 2533 bytes)
  Assembling PDF...
  Finalizing document...
  Total: 10 pages in 2:01
Done.  prep+ocr 101.2s / asm 20.2s = 121.4s total

(base) bgu@z30b:~/../TrulyFreeOCR$
