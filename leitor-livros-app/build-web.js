// Copia o app web para www/ e troca os scripts da internet por copias locais (funciona offline).
const fs = require('fs');
fs.rmSync('www', {recursive: true, force: true});
fs.mkdirSync('www/vendor', {recursive: true});
fs.copyFileSync('node_modules/jszip/dist/jszip.min.js', 'www/vendor/jszip.min.js');
fs.copyFileSync('node_modules/pdfjs-dist/build/pdf.min.js', 'www/vendor/pdf.min.js');
fs.copyFileSync('node_modules/pdfjs-dist/build/pdf.worker.min.js', 'www/vendor/pdf.worker.min.js');
let h = fs.readFileSync('../leitor-livros/index.html', 'utf8');
h = h.replace(/https:\/\/cdnjs\.cloudflare\.com\/ajax\/libs\/jszip\/[\d.]+\/jszip\.min\.js/, 'vendor/jszip.min.js')
     .replace(/https:\/\/cdnjs\.cloudflare\.com\/ajax\/libs\/pdf\.js\/[\d.]+\/pdf\.min\.js/, 'vendor/pdf.min.js')
     .replace(/https:\/\/cdnjs\.cloudflare\.com\/ajax\/libs\/pdf\.js\/[\d.]+\/pdf\.worker\.min\.js/, 'vendor/pdf.worker.min.js');
if (/cdnjs/.test(h)) throw new Error('sobrou link de CDN');
fs.writeFileSync('www/index.html', h);
console.log('www/ pronto');
