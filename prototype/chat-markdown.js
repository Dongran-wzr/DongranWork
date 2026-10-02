(() => {
  const escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  function render(text,citation){
    if(!window.marked||!window.DOMPurify)return escape(text);
    const parser=new marked.Marked({gfm:true,breaks:false,renderer:{
      html(token){return escape(token.text);},
      image(token){return escape(token.text||'图片');},
      link(token){
        const match=/^knowledge:\/\/([a-f0-9-]{36})\/([a-f0-9-]{36})$/i.exec(token.href);
        if(match&&citation)return citation({id:match[1],chunkId:match[2],name:token.text});
        let url;try{url=new URL(token.href);}catch{return this.parser.parseInline(token.tokens);}
        const label=this.parser.parseInline(token.tokens);
        if(!['http:','https:','mailto:'].includes(url.protocol)||url.username||url.password)return label;
        return `<a href="${escape(url.href)}" target="_blank" rel="noopener noreferrer">${label}</a>`;
      }
    }});
    return DOMPurify.sanitize(parser.parse(String(text||'')),{
      ALLOWED_TAGS:['p','br','h1','h2','h3','h4','h5','h6','strong','em','del','blockquote','ul','ol','li','pre','code','hr','table','thead','tbody','tr','th','td','a','button','span','i'],
      ALLOWED_ATTR:['href','target','rel','class','type','start','align','data-cite-document','data-cite-chunk','data-cite-query','data-lucide'],
      ALLOW_DATA_ATTR:false
    });
  }
  window.DongranMarkdown={render};
})();
