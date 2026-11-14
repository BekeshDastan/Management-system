/* Theme-aware accent and cover choices, kept only in this page. */
const apAccents=[['fern','Лесной'],['berry','Ягодный'],['ocean','Морской']];
function apState(){return state.appearance||={saved:{accent:'fern',cover:5},draft:null,initial:''};}
function selectedCoverTile(fallback=0){const a=apState();return (state.screen==='appearance'?a.draft:a.saved)?.cover??fallback;}
function apApply(){const a=apState();document.body.dataset.accent=(state.screen==='appearance'?a.draft:a.saved)?.accent||'fern';}
function appearanceView(){
 const d=apState().draft;return `<div class="screen appearance-screen">${header('ap-cancel','Ваше оформление','Выберите настроение интерфейса. Можно посмотреть и отменить.')}<section class="card stack"><h3>Цветовой акцент</h3><div class="stack">${apAccents.map(([id,label])=>`<button class="choice" data-action="ap-accent" data-value="${id}" aria-pressed="${d.accent===id}"><span class="accent-swatch ${id}" aria-hidden="true"></span>${label}</button>`).join('')}</div></section><section class="stack"><h3>Обложка дневника</h3><div class="cover-options">${[0,1,2,3,4,5].map(tile=>`<button class="cover-option" data-action="ap-cover" data-value="${tile}" aria-pressed="${d.cover===tile}" aria-label="Обложка ${tile+1}">${foodPhoto(tile,'','Вариант обложки '+(tile+1))}<span>${d.cover===tile?'✓ Выбрана':'Обложка '+(tile+1)}</span></button>`).join('')}</div></section><div class="hero-photo">${foodPhoto(d.cover)}<div class="hero-caption"><span class="eyebrow">Предпросмотр</span><h2>В вашем стиле.</h2></div></div><button class="button" data-action="ap-save">Сохранить оформление</button><button class="button plain" data-action="ap-cancel">Отмена</button><p class="small muted">Акцент работает в обеих темах. Скрыть обложку и изменить порядок карточек можно в настройке главной.</p></div>`;
}
function appearanceAction(a,b){
 if(a==='appearance'){const p=apState();p.draft=structuredClone(p.saved);p.initial=JSON.stringify(p.draft);state.appearanceReturn=state.screen==='tools'?'tools':'account';go('appearance');return true;}
 if(a==='ap-accent'){if(apAccents.some(v=>v[0]===b.dataset.value)){apState().draft.accent=b.dataset.value;render();}return true;}
 if(a==='ap-cover'){const v=Number(b.dataset.value);if(Number.isInteger(v)&&v>=0&&v<6){apState().draft.cover=v;render();}return true;}
 if(a==='ap-save'){demoWrite(()=>{apState().saved=structuredClone(apState().draft);go(state.appearanceReturn||'account');});return true;}
 if(a==='ap-cancel'){
  const back=()=>go(state.appearanceReturn||'account');if(JSON.stringify(apState().draft)!==apState().initial)modal('Отменить оформление?','Предпросмотр будет закрыт, вернётся сохранённый цвет и обложка.','Отменить изменения',back,'Продолжить выбор');else back();return true;
 }
 return false;
}
