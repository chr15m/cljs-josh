[Scittle](https://github.com/babashka/scittle/) cljs live-reloading server.

[A YouTube video about `cljs-josh`](https://youtu.be/4tbjE0_W-58).

Start the `josh` watch server:

```shell
npm install cljs-josh
npx josh
```

Then visit your [Scittle-enabled index.html](./example/index.html) at <http://localhost:8000>.

When you save your .cljs files they will be hot-loaded into the browser running Scittle.

You can also install the `josh` command globally: `npm i -g cljs-josh`.

Bootstrap a basic Scittle project with `josh --init`.

### Example

See [the example](./example) for a basic project to start with.

Start the server to try it out:

```shell
cd example
npx josh
```

### Features

I wanted a Scittle dev experience with these features:

- No build step (the Scittle default).
- Zero configuration required.
- Live reloading on file change, like shadow-cljs.
- Both cljs and CSS files live-reloaded.
- Installable with `npm install`.
- Pure JavaScript, no Java/binary dependency.
- Minimal library deps.

Josh is built on [`nbb`](https://github.com/babashka/nbb/).
