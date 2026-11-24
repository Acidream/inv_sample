function fn() {
  karate.configure('retry', { count: 40, interval: 250 });
  return {
    baseUrl: karate.properties['baseUrl'],
    hooks: Java.type('com.example.investments.acceptance.TestHooks')
  };
}
