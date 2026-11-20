export class MemoryBucket {
 constructor(){this.objects=new Map();this.puts=0;}
 async head(key){const item=this.objects.get(key);return item?{key,size:item.bytes.length,customMetadata:item.options.customMetadata}:null;}
 async get(key){const item=this.objects.get(key);return item?{body:item.bytes.slice(),customMetadata:item.options.customMetadata}:null;}
 async put(key,bytes,options={}){if(options.onlyIf?.etagDoesNotMatch==='*'&&this.objects.has(key))return null;this.puts++;this.objects.set(key,{bytes:new Uint8Array(bytes).slice(),options});return {key};}
 async delete(key){this.objects.delete(key);}
}
