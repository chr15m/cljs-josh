#!/usr/bin/env node

import { addClassPath, loadFile } from 'nbb';
import { fileURLToPath } from 'url';
import { dirname, basename, resolve } from 'path';

const __dirname = fileURLToPath(dirname(import.meta.url));
const __filename = basename(import.meta.url);

addClassPath(resolve(__dirname));
await loadFile(resolve(__dirname, __filename.replace(".mjs", ".cljs")));
