[Scittle](https://github.com/babashka/scittle/) cljs live-reloading server.

Start the `josh` watch server:

```shell
npm install cljs-josh
npx josh
```

Then visit your [Scittle-enabled index.html](https://github.com/babashka/scittle/blob/main/resources/public/index.html) at <http://localhost:8000>.

When you save your .cljs files they will be hot-loaded into the browser running Scittle.

### Why

I wanted a dev experience with Scittle with these features:

- No build step (the Scittle default)
- Live reloading on file change like shadow-cljs
- Both cljs and CSS files live-reloaded
- Installable with `npm install`
- Pure JavaScript, no Java dependency
- Minimal library dependencies

Josh is built on [`nbb`](https://github.com/babashka/nbb/).
