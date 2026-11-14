/* Per-session home presentation preferences; diary records are never edited here. */
const hlBlocks=[['hero','Обложка','Фото и приветствие дня'],['nutrition','Питание','Калории, БЖУ и полнота дневника'],['meals','Приёмы пищи','Записи еды и быстрые действия'],['journal','Мой день','Фото, настроение и заметка'],['prompt','Подсказка дня','Мягкая подсказка по времени суток'],['measurements','Вес и сон','Измерения и переход к формам'],['level','Уровень дневника','Игровой прогресс за заполненные даты']];
function hlDefaults(){return hlBlocks.map(([id])=>({id,visible:true}));}
function hlNormalize(list){
 const seen=new Set(),result=[];
 (Array.isArray(list)?list:[]).forEach(item=>{if(item&&hlBlocks.some(([id])=>id===item.id)&&!seen.has(item.id)){seen.add(item.id);result.push({id:item.id,visible:item.visible!==false});}});
 hlBlocks.forEach(([id])=>{if(!seen.has(id))result.push({id,visible:true});});return result;
}
function hlState(){
 if(!state.homeLayout)state.homeLayout={saved:hlDefaults(),draft:null,initial:'',message:''};
 return state.homeLayout;
}
function hlDirty(){const r=hlState();return JSON.stringify(r.draft)!==r.initial;}
function hlVisibleText(list){return 'Видимых блоков: '+list.filter(b=>b.visible).length+' из '+hlBlocks.length;}
function homeLayoutView(){
 const r=hlState();if(!r.draft){r.draft=structuredClone(hlNormalize(r.saved));r.initial=JSON.stringify(r.draft);}
 return `<div class="screen home-layout-screen">${header('hl-cancel','Моя главная','Оставьте нужные блоки и расположите их так, как удобно вам.')}<p class="notice">Дата, быстрые переходы, отметка заполнения дня и навигация всегда доступны. Скрытые записи сохраняются в дневнике и отчётах.</p>${r.message?`<p class="toast" role="status">${esc(r.message)}</p>`:''}<p class="small muted" id="hl-visible-count" aria-live="polite">${hlVisibleText(r.draft)}</p><div class="stack">${r.draft.map((item,index)=>{const [,name,description]=hlBlocks.find(([id])=>id===item.id);return `<section class="card stack home-layout-row"><label class="game-setting" for="hl-visible-${item.id}"><input type="checkbox" id="hl-visible-${item.id}" ${item.visible?'checked':''}><span>${name}<small class="muted" style="display:block;font-weight:400">${description}</small></span></label><div class="row wrap"><button class="button secondary" data-action="hl-up" data-block="${item.id}" aria-label="Поднять блок ${name}" ${index===0?'disabled':''}>↑ Выше</button><button class="button secondary" data-action="hl-down" data-block="${item.id}" aria-label="Опустить блок ${name}" ${index===r.draft.length-1?'disabled':''}>↓ Ниже</button></div></section>`;}).join('')}</div><button class="button" data-action="hl-save">Сохранить главную</button><button class="button plain" data-action="hl-cancel">Отмена</button><button class="button secondary" data-action="hl-reset">Вернуть стандартный вид</button><p class="footer-note">Настройки действуют до перезагрузки этого прототипа.</p></div>`;
}
function homeLayoutInput(id,value,checked){
 if(state.screen!=='home-layout'||!id.startsWith('hl-visible-'))return false;
 const item=hlState().draft?.find(item=>item.id===id.slice(11));if(!item)return false;
 item.visible=Boolean(checked);hlState().message='';const counter=document.getElementById('hl-visible-count');if(counter)counter.textContent=hlVisibleText(hlState().draft);return true;
}
function homeLayoutAction(action,button){
 if(action==='home-layout'){const r=hlState();r.draft=structuredClone(hlNormalize(r.saved));r.initial=JSON.stringify(r.draft);r.message='';go('home-layout');return true;}
 if(!action.startsWith('hl-'))return false;const r=hlState();
 if(action==='hl-up'||action==='hl-down'){
  const index=r.draft?.findIndex(item=>item.id===button.dataset.block)??-1,target=index+(action==='hl-up'?-1:1);
  if(index>=0&&target>=0&&target<r.draft.length){[r.draft[index],r.draft[target]]=[r.draft[target],r.draft[index]];r.message='';render();}return true;
 }
 if(action==='hl-save'){
  const saved=hlNormalize(r.draft);demoWrite(()=>{r.saved=structuredClone(saved);r.draft=null;r.initial='';r.message='';go('home');state.message='Главная настроена';render();});return true;
 }
 if(action==='hl-cancel'){
  const leave=()=>{r.draft=null;r.initial='';r.message='';go('home');};
  if(hlDirty())modal('Выйти без сохранения?','Главная сохранит прежний порядок и видимость блоков.','Выйти без сохранения',leave,'Продолжить настройку');else leave();return true;
 }
 if(action==='hl-reset'){
  modal('Вернуть стандартную главную?','Порядок и видимость блоков будут сохранены по умолчанию. Текущие настройки черновика сбросятся.','Восстановить вид',()=>demoWrite(()=>{r.saved=hlDefaults();r.draft=hlDefaults();r.initial=JSON.stringify(r.draft);r.message='Стандартная главная восстановлена';render();}));return true;
 }
 return false;
}
function homeLayoutAfterRender(){
 if(state.screen!=='home')return;const host=app.querySelector('.screen.home');if(!host)return;
 const anchor=host.querySelector('[data-home-end]');if(!anchor)return;
 const saved=hlNormalize(hlState().saved);saved.forEach(item=>{const block=host.querySelector('[data-home-block="'+item.id+'"]');if(!block)return;block.hidden=!item.visible;block.classList.toggle('hidden',!item.visible);anchor.parentNode.insertBefore(block,anchor);});
 host.querySelectorAll('.home-layout-empty').forEach(node=>node.remove());
 if(saved.every(item=>!item.visible)){
  const hint=document.createElement('section');hint.className='notice home-layout-empty';hint.innerHTML='<p>Все настраиваемые блоки скрыты. Можно вернуть любой из них.</p><button class="link" data-action="home-layout">Настроить главную →</button>';anchor.parentNode.insertBefore(hint,anchor);
 }
}
