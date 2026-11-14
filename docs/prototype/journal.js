/* Local preview only: user-selected images never leave the browser. */
const journalMoods=[['bright','☀','Бодро'],['calm','☁','Спокойно'],['tired','☾','Усталость'],['mixed','≈','По-разному']];
function journalSummary(){
 const d=day(),mood=journalMoods.find(m=>m[0]===d.mood);
 return `<section class="card daily-journal stack"><div class="row"><div><span class="eyebrow">За кадром цифр</span><h3>Мой день</h3></div><button class="icon-button" data-action="journal" aria-label="Фото, настроение и заметка">✎</button></div><p class="small muted">${mood?mood[1]+' '+mood[2]:'Как вы сегодня? Можно просто оставить пару слов.'}</p>${d.note?`<p class="journal-note">${esc(d.note)}</p>`:''}${d.photos?.length?`<div class="photo-grid">${d.photos.map(p=>`<figure class="journal-photo"><img src="${esc(p.url)}" alt="${esc(p.caption||'Фото дня')}" loading="lazy"><figcaption>${esc(p.caption||'Момент дня')}${p.meal===null?'':' · '+esc(meals[p.meal]||'Приём пищи')}</figcaption></figure>`).join('')}</div>`:''}<button class="link" data-action="journal">${d.photos?.length?'Открыть фотодневник':'Добавить фото и заметку'} →</button></section>`;
}
function journalView(){
 const d=state.journalDraft;
 return `<div class="screen journal-screen">${header('journal-cancel','Мой день','Фотографии, настроение и маленькие заметки — без оценок и баллов.')}<p class="small muted">${dateLabel(state.journalDate)}</p><section class="card stack"><h3>Как прошёл день?</h3><div class="mood-options">${journalMoods.map(([id,icon,label])=>`<button class="mood-option" data-action="journal-mood" data-value="${id}" aria-pressed="${d.mood===id}"><span aria-hidden="true">${icon}</span>${label}</button>`).join('')}</div><label class="field" for="journal-note">Пара слов для себя<textarea id="journal-note" rows="4" maxlength="500" placeholder="Что хочется запомнить?">${esc(d.note)}</textarea></label><p class="small muted" id="journal-counter">${d.note.length} / 500</p></section><section class="stack"><div class="row"><h2>Моменты дня</h2><span class="pill">${d.photos.length} / 4</span></div><p class="small muted">JPEG, PNG или WebP, до 5 МБ. Фото остаётся в памяти этой страницы. Выбирайте тестовые изображения: после перезагрузки они исчезнут.</p><label class="field">Добавить своё фото<input type="file" id="journal-file" accept="image/jpeg,image/png,image/webp" ${d.photos.length>=4||state.journalLoading?'disabled':''}></label>${state.journalLoading?'<p class="notice" role="status">Открываем фото…</p>':''}<p class="error" id="journal-photo-error" role="alert">${esc(state.journalPhotoError||'')}</p><div class="photo-grid">${d.photos.map((p,i)=>`<figure class="journal-photo stack"><img src="${esc(p.url)}" alt="Предпросмотр фото ${i+1}"><label class="field">Подпись<input id="journal-caption-${i}" maxlength="80" value="${esc(p.caption)}" placeholder="Например, завтрак дома"></label><label class="field">Связать с приёмом<select id="journal-meal-${i}"><option value="" ${p.meal===null?'selected':''}>Просто момент дня</option>${state.mealOrder.map(id=>`<option value="${id}" ${p.meal===id?'selected':''}>${esc(meals[id])}${state.archivedMeals.includes(id)?' · архив':''}</option>`).join('')}</select></label><button class="link" data-action="journal-photo-remove" data-index="${i}">Убрать фото</button></figure>`).join('')}</div><p class="notice">По фотографии ничего не рассчитывается. Еду и КБЖУ добавляйте вручную в дневник.</p></section><button class="button" data-action="journal-save" ${state.journalLoading?'disabled':''}>Сохранить день</button><button class="button plain" data-action="journal-cancel">Отмена</button></div>`;
}
function journalDirty(){return JSON.stringify(state.journalDraft)!==state.journalInitial;}
function journalInput(id,value){
 if(state.screen!=='journal')return false;
 const d=state.journalDraft;
 if(id==='journal-note'){d.note=value.slice(0,500);document.getElementById('journal-counter').textContent=d.note.length+' / 500';return true;}
 const caption=/^journal-caption-(\d+)$/.exec(id),meal=/^journal-meal-(\d+)$/.exec(id);
 if(caption&&d.photos[caption[1]]){d.photos[caption[1]].caption=value.slice(0,80);return true;}
 if(meal&&d.photos[meal[1]]){d.photos[meal[1]].meal=value===''?null:Number(value);return true;}
 return false;
}
async function journalFile(file){
 if(!file||state.screen!=='journal'||state.journalLoading)return;
 const draft=state.journalDraft,date=state.journalDate;
 state.journalPhotoError='';
 if(!['image/jpeg','image/png','image/webp'].includes(file.type))state.journalPhotoError='Выберите JPEG, PNG или WebP.';
 else if(file.size>5*1024*1024)state.journalPhotoError='Фото слишком большое. Выберите файл до 5 МБ.';
 else if(draft.photos.length>=4)state.journalPhotoError='На день можно добавить до четырёх фото.';
 if(state.journalPhotoError){render();return;}
 state.journalLoading=true;render();
 try{
  const url=await new Promise((resolve,reject)=>{const r=new FileReader();r.onload=()=>resolve(r.result);r.onerror=()=>reject(new Error('read'));r.readAsDataURL(file);});
  const image=new Image();image.src=url;await image.decode();
  if(!image.naturalWidth||image.naturalWidth>16000||image.naturalHeight>16000)throw new Error('dimensions');
  if(state.screen==='journal'&&state.journalDraft===draft&&state.journalDate===date&&draft.photos.length<4)draft.photos.push({url,caption:'',meal:state.journalMeal??null});
 }catch{if(state.journalDraft===draft)state.journalPhotoError='Не удалось открыть изображение. Попробуйте другой файл.';}
 finally{if(state.journalDraft===draft){state.journalLoading=false;if(state.screen==='journal')render();}}
}
function journalAction(a,b){
 if(a==='journal'){
  state.journalDate=state.date;state.journalMeal=b?.dataset.meal===undefined?null:Number(b.dataset.meal);const d=day();state.journalDraft=structuredClone({mood:d.mood||'',note:d.note||'',photos:d.photos||[]});state.journalInitial=JSON.stringify(state.journalDraft);state.journalPhotoError='';state.journalLoading=false;go('journal');return true;
 }
 if(a==='journal-mood'){state.journalDraft.mood=state.journalDraft.mood===b.dataset.value?'':b.dataset.value;render();return true;}
 if(a==='journal-photo-remove'){state.journalDraft.photos.splice(Number(b.dataset.index),1);state.journalPhotoError='';render();return true;}
 if(a==='journal-save'){
  if(state.journalLoading)return true;
  demoWrite(()=>{ensureDay(state.journalDate);Object.assign(state.days[state.journalDate],structuredClone(state.journalDraft));state.date=state.journalDate;go('home');state.message='Моменты дня сохранены';render();});return true;
 }
 if(a==='journal-cancel'){
  if(journalDirty())modal('Выйти без сохранения?','Фото, настроение и заметка останутся без изменений.','Выйти без сохранения',()=>go('home'),'Продолжить');else go('home');return true;
 }
 return false;
}
