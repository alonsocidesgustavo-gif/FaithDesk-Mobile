const { contextBridge } = require('electron');

// Expõe versão do app
contextBridge.exposeInMainWorld('app', {
  version: require('./package.json').version
});
