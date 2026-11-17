// Run after testDebugUnitTest: node app/src/test/js/t234-reader.js
// Executes the production pagination script with deterministic DOM geometry, not a WebView.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(process.argv[2] || 'app/build/t234-reader.html', 'utf8');
const script = html.match(/<script>([\s\S]*)<\/script>/)[1];
let charsPerPage = 100;
const width = 360, margin = width * .07;
let translate = 0, time = 0, nextFrame = 1;
const frames = new Map(), listeners = {}, moved = [], ready = [];
let fontsReady;
const fonts = new Promise(resolve => { fontsReady = resolve; });
const book = {children: [], style: {}, scrollWidth: 20 * width};
Object.defineProperty(book.style, 'transform', {set(value) {
    translate = Number(value.match(/translate3d\(([-\d.]+)(?:px)?,/)[1]);
}});
const rectAt = absolute => {
    const within = absolute % charsPerPage;
    const left = margin + Math.floor(absolute / charsPerPage) * width + (within % 10) * 8 + translate;
    const top = margin + Math.floor(within / 10) * 20;
    return {left, right: left + 8, top, bottom: top + 20, width: 8, height: 20};
};
let absolute = 0;
for (const lengths of [[500, 700], [800]]) {
    const start = absolute;
    const section = {nodes: [], parentNode: book, textContent: 'x'.repeat(lengths.reduce((a,b) => a+b)),
        getBoundingClientRect() {
            const first = rectAt(start), last = rectAt(start + this.textContent.length - 1);
            return {...first, right: last.right, width: last.right - first.left};
        }};
    for (const length of lengths) {
        section.nodes.push({nodeValue: 'x'.repeat(length), absolute, parentNode: section});
        absolute += length;
    }
    book.children.push(section);
}
const document = {
    readyState: 'loading', fonts: {ready: fonts},
    getElementById() { return book; },
    createTreeWalker(section) { let i=0; return {nextNode() { return section.nodes[i++] ?? null; }}; },
    createRange() {
        let node, start;
        return {setStart(n, offset) { node=n; start=offset; }, setEnd() {},
            selectNodeContents(section) { node=section.nodes.at(-1); start=node.nodeValue.length-1; },
            collapse() {}, getBoundingClientRect() { return rectAt(node.absolute + start); }};
    },
};
const window = {addEventListener(type, fn) { listeners[type]=fn; },
    Reader: {moved(...args) { moved.push(args); }, ready(...args) { ready.push(args); }, offset() {}}};
const context = {document, window, Reader: window.Reader, NodeFilter: {SHOW_TEXT: 4},
    requestAnimationFrame(fn) { const id=nextFrame++; frames.set(id,fn); return id; },
    cancelAnimationFrame(id) { frames.delete(id); }, Promise};
vm.runInNewContext(script, context);
function drainFrames() {
    for (let count=0; frames.size; count++) {
        assert.ok(count < 1000, 'spring must settle');
        const batch = [...frames.values()]; frames.clear(); time += 16;
        batch.forEach(fn => fn(time));
    }
}
(async () => {
    assert.equal(moved.length, 0, 'no pre-layout position write');
    listeners.load();
    await Promise.resolve();
    assert.equal(moved.length, 0, 'wait for fonts after images load');
    fontsReady();
    await Promise.resolve(); await Promise.resolve(); drainFrames();
    assert.deepEqual(moved.at(-1).slice(0,3), [10,0,1000], 'restore the saved character after layout');
    assert.deepEqual(ready.at(-1), [20,7]);
    window.reader.goTo(0,500);
    assert.equal(moved.at(-1)[0], 5, 'a text-node boundary belongs to the following node');
    window.reader.settle(11,.88,260); drainFrames();
    assert.deepEqual(moved.at(-1).slice(0,3), [11,0,1100], 'turn finds the first visible glyph');
    window.reader.settle(12,.88,260); drainFrames();
    assert.deepEqual(moved.at(-1).slice(0,3), [12,1,0], 'turn crosses the chapter boundary');
    listeners.load(); await Promise.resolve(); drainFrames();
    assert.deepEqual(moved.at(-1).slice(0,3), [12,1,0], 'late load must not reset to the opening offset');
    charsPerPage=50;
    listeners.resize();
    assert.deepEqual(moved.at(-1).slice(0,3), [24,1,0], 'reflow preserves the character, not the page');
    assert.equal(ready.length, 1, 'initialization only runs once');
    console.log('T234 pagination: 9 regression checks passed');
})().catch(error => { console.error(error); process.exitCode=1; });
