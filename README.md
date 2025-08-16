[Scittle](https://github.com/babashka/scittle/) cljs live-reloading server.

[A YouTube video about `cljs-josh`](https://youtu.be/4tbjE0_W-58).

[Example project](#example-project) | [Features](#features) | [Tips (+ nREPL)](#tips)

Start the `josh` watch server:

```shell
npm install cljs-josh
npx josh
```

Then visit your [Scittle-enabled index.html](./example/index.html) at <http://localhost:8000>.

When you save your .cljs files they will be hot-loaded into the browser running Scittle.

You can also install the `josh` command globally: `npm i -g cljs-josh`.

Bootstrap a basic Scittle project with `josh --init`.

## Example project

See [the example](./example) for a basic project to start with (or use `josh --init`).

Start the server to try it out:

```shell
cd example
npx josh
```

## Features

I wanted a Scittle dev experience with these features:

- No build step (the Scittle default).
- Zero configuration required.
- Live reloading on file change, like shadow-cljs.
- Both cljs and CSS files live-reloaded.
- Installable with `npm install`.
- Pure JavaScript, no Java/binary dependency.
- Minimal library deps.

Josh is built on [`nbb`](https://github.com/babashka/nbb/).

## Tips

- Install `josh` globally with `npm i -g cljs-josh` and then you can just use `josh` to run it.
- Use `josh --init` to download and install the example template into the current folder.

### nREPL support

Josh runs an nREPL proxy which sends all commands to Scittle over a websocket.
It automatically injects the `scittle.nrepl.js` script tags into the HTML at runtime.

The following editor specific instructions assume you have already:

- run `josh`.
  `nREPL server started on port 34581 on host 127.0.0.1 - nrepl://127.0.0.1:34581`
- Load `http://localhost:8000` in your browser.
- Opened main.cljs in your editor.

#### VS Code

You need to have the Calva extension installed.

- Click on "REPL ⚡️" in the bottom left.
- Choose "Connect to a running REPL in your project".
- Choose "ClojureScript nREPL Server".

Now you can evaluate forms with alt-Enter.

#### Cider

- Issue `cider-connect-cljs`, use `localhost`, and enter the nREPL port.
- Use `nbb` as REPL type.

Now you can evaluate forms.

If that doesn't get you up and running you may need to use `sesman-link-with-buffer` and select the existing entry.

Note: `cider-inspect` work.

If you have `cider-default-cljs-repl` set in your Emacs config it may also help to comment that out:

```
;;(setq cider-default-cljs-repl 'shadow)
```

#### vim-fireplace

- Run `:CljEval (josh/repl)` to tell vim-fireplace it's in a ClojureScript repl.

Now you can evaluate forms with `cpp` and `cpip` etc.
