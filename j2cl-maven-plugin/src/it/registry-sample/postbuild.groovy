def js = new File(basedir, 'target/registry-sample-1.0/registry-sample/registry-sample.js').text

if (!js.contains('"Hello, Java!"')) {
    throw new IllegalStateException('Contents weren\'t optimized correctly, no "Hello, Java!" string')
}
if (js.contains('python') || js.contains('Python')) {
    throw new IllegalStateException('Contents weren\'t optimized correctly, \'python\' found in output')
}