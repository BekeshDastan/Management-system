/* Presets change only the quantity draft; adding food stays explicit. */
function qpState(){return state.portions||=( {} );}
function qpKey(p){return JSON.stringify([p.id,p.base,p.unit]);}
function qpDefaults(p){return p.base===1?[1,2,3]:p.unit==='г'?[100,150,250]:p.unit==='мл'?[100,200,250]:[p.base,p.base*2,p.base*3];}
function qpValues(p){return qpState()[qpKey(p)]||qpDefaults(p);}
function quickPortionsMarkup(p){return `<section class="stack quick-portions"><div class="row wrap"><h3>Быстрые порции</h3><button class="link" data-action="qp-edit">Настроить</button></div><div class="preset-buttons">${qpValues(p).map(q=>`<button class="button secondary" data-action="qp-use" data-quantity="${q}" aria-pressed="${num(state.quantity)===q}">${fmt(q)} ${esc(p.unit)}</button>`).join('')}</div><p class="small muted">Выберите количество, затем добавьте еду в дневник.</p></section>`;}
function qpError(){
 const rows=state.portionDraft.filter(v=>v.trim()!=='');
 if(!rows.length)return 'Укажите хотя бы одну порцию.';
 if(rows.some(v=>!portionValues(productSnapshot(selected()),num(v))))return 'Укажите положительные количества; итог должен помещаться в числовой диапазон.';
 if(new Set(rows.map(num)).size!==rows.length)return 'Уберите повторяющиеся количества.';
 return '';
}
function quickPortionsView(){
 const p=selected();return `<div class="screen">${header('qp-cancel','Быстрые порции',esc(p.name+' · '+p.unit))}<p class="notice">До пяти кнопок для этого продукта. Пустые поля не сохраняются. При изменении базы или единицы используются отдельные настройки.</p><div class="stack">${state.portionDraft.map((v,i)=>field('qp-'+i,'Порция '+(i+1)+', '+esc(p.unit),v,'inputmode="decimal" autocomplete="off"')).join('')}</div><p class="error" id="qp-error" role="alert">${esc(qpError())}</p><button class="button" data-action="qp-save" id="qp-save" ${qpError()?'disabled':''}>Сохранить кнопки</button><button class="button secondary" data-action="qp-defaults">Вернуть стандартные</button><button class="button plain" data-action="qp-cancel">Отмена</button></div>`;
}
function quickPortionsInput(id,value){
 const match=/^qp-([0-4])$/.exec(id);if(!match||state.screen!=='portion-presets')return false;
 state.portionDraft[Number(match[1])]=value;const error=qpError();document.getElementById('qp-error').textContent=error;document.getElementById('qp-save').disabled=!!error;return true;
}
function quickPortionsAction(a,b){
 if(a==='qp-use'&&state.screen==='portion'){
  const q=Number(b.dataset.quantity);if(!qpValues(selected()).includes(q))return true;
  const input=document.getElementById('quantity');input.value=String(q);input.dispatchEvent(new Event('input',{bubbles:true}));
  app.querySelectorAll('[data-action="qp-use"]').forEach(button=>button.setAttribute('aria-pressed',String(Number(button.dataset.quantity)===q)));return true;
 }
 if(a==='qp-edit'){
  state.portionDraft=qpValues(selected()).map(String);while(state.portionDraft.length<5)state.portionDraft.push('');state.portionInitial=JSON.stringify(state.portionDraft);go('portion-presets');return true;
 }
 if(a==='qp-save'){
  if(qpError())return true;demoWrite(()=>{qpState()[qpKey(selected())]=state.portionDraft.filter(v=>v.trim()!=='').map(num);go('portion');});return true;
 }
 if(a==='qp-defaults'){
  modal('Вернуть стандартные порции?','Изменится только текущий черновик кнопок. Количество съеденного и история останутся прежними.','Вернуть стандартные',()=>{state.portionDraft=qpDefaults(selected()).map(String);while(state.portionDraft.length<5)state.portionDraft.push('');render();});return true;
 }
 if(a==='qp-cancel'){
  if(JSON.stringify(state.portionDraft)!==state.portionInitial)modal('Выйти без сохранения?','Изменения быстрых кнопок будут отброшены.','Выйти без сохранения',()=>go('portion'),'Продолжить');else go('portion');return true;
 }
 return false;
}
