/* Read-only indexes over saved diary records. No independent photo or nutrition store. */
function htState(){
 if(!state.historyTools)state.historyTools={feed:{query:'',from:'',to:'',meal:'all',limit:12},search:{query:'',from:'',to:'',meal:'all',limit:12},selected:null,returnTo:null};
 return state.historyTools;
}
function htDateValid(date){return calendarDay(date)!==null&&date<=TODAY;}
function htFilterError(filter){
 if(filter.from&&!htDateValid(filter.from)||filter.to&&!htDateValid(filter.to))return 'Выберите допустимые даты не позднее '+dateLabel(TODAY)+'.';
 if(filter.from&&filter.to&&filter.from>filter.to)return 'Начало периода должно быть не позднее его конца.';
 return '';
}
function htDates(filter){return Object.keys(state.days).filter(date=>htDateValid(date)&&(!filter.from||date>=filter.from)&&(!filter.to||date<=filter.to)).sort((a,b)=>b.localeCompare(a));}
function htQueryMatches(value,query){return String(value||'').toLocaleLowerCase('ru-RU').includes(query.trim().toLocaleLowerCase('ru-RU'));}
function htMealMatches(meal,filter){return filter==='all'||filter==='none'&&meal==null||filter!=='none'&&meal===Number(filter);}
function htMealName(id){return id==null?'Без приёма':meals[id]||'Приём '+id;}
function htMealOptions(selected,includeNone=false){
 const ids=[...new Set([...state.mealOrder,...Object.values(state.days).flatMap(d=>[...(d.entries||[]).map(e=>e.meal),...(d.photos||[]).map(p=>p.meal)])])].filter(Number.isInteger);
 return `<option value="all" ${selected==='all'?'selected':''}>Все приёмы</option>${includeNone?`<option value="none" ${selected==='none'?'selected':''}>Без привязки к приёму</option>`:''}${ids.map(id=>`<option value="${id}" ${String(selected)===String(id)?'selected':''}>${esc(htMealName(id))}${state.archivedMeals.includes(id)?' · архив':''}</option>`).join('')}`;
}
function htPhotos(filter=htState().feed){
 if(htFilterError(filter))return [];
 return htDates(filter).flatMap(date=>(state.days[date].photos||[]).map((photo,index)=>({date,index,photo})).filter(row=>htMealMatches(row.photo.meal,filter.meal)&&htQueryMatches(row.photo.caption,filter.query)).reverse());
}
function htEntries(filter=htState().search){
 if(htFilterError(filter))return [];
 return htDates(filter).flatMap(date=>(state.days[date].entries||[]).map(entry=>({date,entry,complete:state.days[date].complete===true})).filter(row=>htMealMatches(row.entry.meal,filter.meal)&&htQueryMatches(row.entry.name,filter.query)).reverse());
}
function htImageUrl(photo){return typeof photo?.url==='string'&&/^data:image\/(jpeg|png|webp);base64,[A-Za-z0-9+/=\r\n]+$/i.test(photo.url)?photo.url:'';}
function htPhotoMarkup(photo,className=''){
 const url=htImageUrl(photo);return url?`<img class="${className}" src="${esc(url)}" alt="${esc(photo.caption||'Фото из дневника')}" loading="lazy">`:'<p class="notice">Изображение недоступно. Откройте день, чтобы заменить его.</p>';
}
function htFilters(kind){
 const filter=htState()[kind],feed=kind==='feed';
 return `<section class="card stack history-filter"><label class="field" for="ht-${kind}-query">${feed?'Поиск по подписи':'Что найти в дневнике?'}<input id="ht-${kind}-query" value="${esc(filter.query)}" maxlength="100" placeholder="${feed?'Например, завтрак дома':'Название продукта или блюда'}" autocomplete="off"></label><div class="fields">${field('ht-'+kind+'-from','С даты',filter.from,`type="date" min="${MIN_DATE}" max="${TODAY}" aria-describedby="ht-${kind}-error"`)}${field('ht-'+kind+'-to','По дату',filter.to,`type="date" min="${MIN_DATE}" max="${TODAY}" aria-describedby="ht-${kind}-error"`)}</div><label class="field" for="ht-${kind}-meal">Приём пищи<select id="ht-${kind}-meal">${htMealOptions(filter.meal,feed)}</select></label><button class="link" data-action="ht-reset" data-kind="${kind}">Сбросить фильтры</button><p class="small muted">Оставьте даты пустыми для поиска по всей истории.</p></section><p class="error" id="ht-${kind}-error" role="alert">${esc(htFilterError(filter))}</p>`;
}
function htFeedResults(){
 const filter=htState().feed;if(htFilterError(filter))return '<p class="small muted">Исправьте период, чтобы посмотреть фотографии.</p>';
 const rows=htPhotos(filter);if(!rows.length)return `<section class="card stack empty-state"><h3>Пока нет подходящих фото</h3><p class="small muted">Здесь появляются только сохранённые фотографии из «Мой день». Иллюстрации каталога в ленту не попадают.</p><button class="button secondary" data-action="home">Открыть дневник</button></section>`;
 return `<p class="small muted" role="status">Найдено фото: ${rows.length}</p><div class="photo-grid feed-grid">${rows.slice(0,filter.limit).map(row=>`<article class="feed-card"><button class="feed-photo-button" data-action="ht-photo-open" data-date="${row.date}" data-index="${row.index}" aria-label="${esc('Открыть фото: '+dateLabel(row.date)+' · '+(row.photo.caption||htMealName(row.photo.meal)))}">${htPhotoMarkup(row.photo)}</button><div class="feed-caption stack"><p class="small"><strong>${dateLabel(row.date)}</strong></p><p class="small muted">${esc(htMealName(row.photo.meal))}${state.archivedMeals.includes(row.photo.meal)?' · архив':''}</p>${row.photo.caption?`<p class="small">${esc(row.photo.caption)}</p>`:''}<button class="link" data-action="ht-photo-open" data-date="${row.date}" data-index="${row.index}">Посмотреть →</button></div></article>`).join('')}</div>${rows.length>filter.limit?`<button class="button secondary" data-action="ht-more" data-kind="feed">Ещё фотографии · ${rows.length-filter.limit}</button>`:''}`;
}
function htSearchResults(){
 const filter=htState().search;if(htFilterError(filter))return '<p class="small muted">Исправьте период, чтобы посмотреть записи.</p>';
 const rows=htEntries(filter);if(!rows.length)return `<section class="card stack empty-state"><h3>Записи не найдены</h3><p class="small muted">Измените запрос или фильтры. Здесь ищется только внесённая еда; планы, шаблоны и черновики не учитываются.</p></section>`;
 return `<p class="small muted" role="status">Найдено записей: ${rows.length}</p>${rows.slice(0,filter.limit).map(row=>`<article class="card stack history-entry"><div class="title-row"><h3>${esc(row.entry.name)}</h3><span class="pill ${row.complete?'complete':''}">${row.complete?'День заполнен':'Частичный день'}</span></div><p class="small muted">${dateLabel(row.date)} · ${esc(htMealName(row.entry.meal))}${state.archivedMeals.includes(row.entry.meal)?' · архив':''}</p><p class="small">${esc(row.entry.amount||'Внесённая порция')}</p>${nutrition(row.entry.values)}<p class="small muted">КБЖУ внесённой порции из истории, без подстановки текущего продукта.</p><div class="row wrap history-result-actions"><button class="link" data-action="ht-open-day" data-kind="search" data-date="${row.date}">Открыть день</button><button class="link" data-action="ht-edit-entry" data-date="${row.date}" data-id="${row.entry.id}">Исправить запись</button></div></article>`).join('')}${rows.length>filter.limit?`<button class="button secondary" data-action="ht-more" data-kind="search">Ещё записи · ${rows.length-filter.limit}</button>`:''}`;
}
function htSelectedPhoto(){
 const selected=htState().selected;if(!selected||!htDateValid(selected.date))return null;
 const photos=state.days[selected.date]?.photos||[],index=selected.id!==undefined?photos.findIndex(photo=>photo.id===selected.id):photos[selected.index]?.url===selected.url?selected.index:photos.findIndex(photo=>photo.url===selected.url);
 return index<0?null:{date:selected.date,index,photo:photos[index]};
}
function historyToolsView(){
 const ui=htState();
 if(state.screen==='photo-feed')return `<div class="screen history-tools-screen photo-feed-screen">${header('home','Фотолента','Моменты из вашего дневника. Сначала — новые даты.')}<button class="link" data-action="diary-search">Поиск по дневнику →</button>${htFilters('feed')}<div class="stack history-results" id="ht-feed-results">${htFeedResults()}</div><p class="footer-note">Только сохранённые фото этой страницы. После перезагрузки они исчезнут.</p></div>`;
 if(state.screen==='diary-search')return `<div class="screen history-tools-screen diary-search-screen">${header('home','Поиск по дневнику','Найдите блюдо, откройте день или исправьте прошлую запись.')}<button class="link" data-action="photo-feed">Открыть фотоленту →</button>${htFilters('search')}<div class="stack history-results" id="ht-search-results">${htSearchResults()}</div></div>`;
 if(state.screen==='photo-detail'){
  const row=htSelectedPhoto();if(!row)return `<div class="screen history-tools-screen">${header('photo-feed','Фото недоступно','Фотография была удалена или изменена.')}<button class="button secondary" data-action="photo-feed">Вернуться к ленте</button></div>`;
  return `<div class="screen history-tools-screen photo-detail-screen">${header('photo-feed','Момент дня')}<p class="small muted">${dateLabel(row.date)} · ${esc(htMealName(row.photo.meal))}${state.archivedMeals.includes(row.photo.meal)?' · архив':''}</p><figure class="photo-detail-figure">${htPhotoMarkup(row.photo,'photo-detail-image')}<figcaption class="small">${esc(row.photo.caption||'Без подписи')}</figcaption></figure><button class="button" data-action="ht-open-day" data-kind="feed" data-date="${row.date}">Открыть день</button><button class="button secondary" data-action="ht-photo-journal">Изменить фото и подпись</button><p class="small muted">Удаление фото и изменение привязки доступны в «Мой день». Это не изменит записи питания.</p></div>`;
 }
 return '';
}
function htRefresh(kind){
 const filter=htState()[kind],error=document.getElementById('ht-'+kind+'-error'),host=document.getElementById('ht-'+kind+'-results');
 if(error)error.textContent=htFilterError(filter);if(host)host.innerHTML=kind==='feed'?htFeedResults():htSearchResults();
 ['from','to'].forEach(key=>{const input=document.getElementById('ht-'+kind+'-'+key);if(input)input.setAttribute('aria-invalid',String(Boolean(htFilterError(filter))));});
}
function historyToolsInput(id,value){
 const match=/^ht-(feed|search)-(query|from|to|meal)$/.exec(id);if(!match||!['photo-feed','diary-search'].includes(state.screen))return false;
 const [,kind,key]=match,filter=htState()[kind],next=key==='query'?value.slice(0,100):value;if(filter[key]===next)return true;filter[key]=next;filter.limit=12;htRefresh(kind);return true;
}
function htReturn(){
 const ui=htState(),destination=ui.returnTo?.screen||'diary-search';ui.returnTo=null;go(destination);
}
function historyToolsAction(action,button){
 if(action==='photo-feed'||action==='diary-search'){htState();go(action);return true;}
 if(action==='ht-reset'){
  const kind=button.dataset.kind;if(!['feed','search'].includes(kind))return true;htState()[kind]={query:'',from:'',to:'',meal:'all',limit:12};render();return true;
 }
 if(action==='ht-more'){
  const kind=button.dataset.kind;if(['feed','search'].includes(kind)){htState()[kind].limit+=12;htRefresh(kind);}return true;
 }
 if(action==='ht-photo-open'){
  const date=button.dataset.date,index=Number(button.dataset.index),photo=state.days[date]?.photos?.[index];if(!htDateValid(date)||!photo)return true;
  photo.id??=state.nextId++;htState().selected={date,index,id:photo.id,url:photo.url};go('photo-detail');return true;
 }
 if(action==='ht-open-day'){
  const date=button.dataset.date;if(!htDateValid(date)||!state.days[date])return true;htState().returnTo={screen:button.dataset.kind==='feed'?'photo-feed':'diary-search',date};state.date=date;go('home');return true;
 }
 if(action==='ht-edit-entry'){
  const date=button.dataset.date,id=Number(button.dataset.id);if(!htDateValid(date)||!state.days[date]?.entries?.some(entry=>entry.id===id))return true;
  htState().returnTo={screen:'diary-search',date};state.date=date;historyAction('edit-entry',{dataset:{id:String(id)}});return true;
 }
 if(action==='ht-photo-journal'){
  const row=htSelectedPhoto();if(!row)return true;htState().returnTo={screen:'photo-feed',date:row.date};state.date=row.date;journalAction('journal',{dataset:{}});return true;
 }
 if(action==='ht-return'){
  const dirty=state.screen==='entry-edit'?JSON.stringify(state.entryDraft)!==state.entryInitial:state.screen==='journal'?journalDirty():false;
  if(dirty)modal('Вернуться без сохранения?','Изменения текущего черновика не попадут в дневник.','Выйти без сохранения',htReturn,'Продолжить ввод');else htReturn();return true;
 }
 return false;
}
function historyToolsAfterRender(){
 const origin=state.historyTools?.returnTo;if(!origin||origin.date!==state.date||!['home','entry-edit','journal'].includes(state.screen))return;
 const screen=app.querySelector('.screen');if(!screen||screen.querySelector('.history-return'))return;
 const button=document.createElement('button');button.className='back history-return';button.dataset.action='ht-return';button.textContent=origin.screen==='photo-feed'?'← К фотоленте':'← К результатам поиска';screen.prepend(button);
}
