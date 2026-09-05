# 保留崩溃堆栈的可读性行号,上报时可还原混淆映射
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# okhttp / coroutines / compose / androidx 均自带 consumer rules,无需额外 keep
