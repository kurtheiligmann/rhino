load("testsrc/assert.js");

function * genf1() {
  yield 1;
  try {
    yield 2;
  } finally {
    yield 3333;
  }
  yield 4444;
}

let g = genf1();
let n = g.next();
assertEquals(n.value, 1);
n = g.next();
assertEquals(n.value, 2);
n = g.next();
assertEquals(n.value, 3333);
n = g.next();
assertEquals(n.value, 4444);
n = g.next();
assertEquals(n.value, undefined);

"success";