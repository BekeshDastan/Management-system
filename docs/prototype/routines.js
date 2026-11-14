/* Meal routines and planning for the temporary, offline UX prototype. */
function rtState(){
 if(!state.routines)state.routines={templates:[],plans:[],sourceDate:state.date,sourceMeal:rtFirstMeal(),selectedTemplate:null,applyDate:state.date,applyMeal:rtFirstMeal(),planDate:state.date,planMode:'day',draft:null,initial:'',error:'',pickProduct:String(state.products[0]?.id||''),pickQuantity:String(state.products[0]?.base||100),pickTemplate:''};
 return state.routines;
}
function rtFirstMeal(){return state.mealOrder.find(id=>!state.archivedMeals.includes(id));}
function rtActiveMeal(id){return state.mealOrder.includes(Number(id))&&!state.archivedMeals.includes(Number(id));}
function rtMaxDate(){return dateFromDay(calendarDay(TODAY)+90);}
function rtDateValid(date,future=false){const n=calendarDay(date);return n!==null&&date<=(future?rtMaxDate():TODAY);}
function rtNormalEntry(entry){
 const snapshot=entry.snapshot?structuredClone(entry.snapshot):{name:entry.name,base:1,unit:'порция',values:[...entry.values],photo:null};
 const quantity=entry.snapshot?Number(entry.quantity):1;
 return {name:entry.name,kind:'portion',snapshot,quantity:String(quantity),sourceId:entry.sourceId};
}
function rtEntryValue(entry){return portionValues(entry.snapshot,num(entry.quantity));}
function rtEntriesResult(entries){
 if(!entries.length)return null;
 const rows=entries.map(e=>{const v=rtEntryValue(e),q=num(e.quantity);return v?{...structuredClone(e),quantity:q,values:[...v],amount:fmt(q)+' '+e.snapshot.unit}:null;});
 if(rows.some(e=>!e))return null;
 const total=rows.reduce((s,e)=>s.map((v,i)=>v+e.values[i]),[0,0,0,0]);
 return total.every(Number.isFinite)?{rows,total}:null;
}
function rtPreview(entries){
 const result=rtEntriesResult(entries);
 return `<section class="card stack"><h3>К добавлению</h3>${entries.map(e=>`<p class="small">${esc(e.name)} · ${esc(String(e.quantity))} ${esc(e.snapshot.unit)} · ${rtEntryValue(e)?fmt(rtEntryValue(e)[0])+' ккал':'проверьте количество'}</p>`).join('')}${nutrition(result?.total||null)}</section>`;
}
function rtDraftError(){
 const r=rtState(),d=r.draft;
 if(!d)return 'Откройте шаблон или план.';
 if(!d.name.trim())return 'Укажите название.';
 if(d.name.trim().length>80)return 'Название — до 80 знаков.';
 if(d.type==='plan'&&!rtDateValid(d.date,true))return 'Выберите допустимую дату — не дальше 90 дней от условного сегодня.';
 if(d.type==='plan'&&!rtActiveMeal(d.meal))return 'Выберите активный приём пищи.';
 if(!rtEntriesResult(d.entries))return 'Добавьте хотя бы одну строку. Количества должны быть положительными, итоги — конечными.';
 return '';
}
function rtDraftRows(){
 const d=rtState().draft;
 return d.entries.length?d.entries.map((e,i)=>`<section class="card stack routine-entry"><div class="row"><h3>${esc(e.name)}</h3><button class="link" data-action="rt-row-remove" data-index="${i}" aria-label="Убрать ${esc(e.name)}">Убрать</button></div><p class="small muted">Снимок: ${fmt(e.snapshot.values[0])} ккал на ${e.snapshot.base} ${esc(e.snapshot.unit)}</p>${field('rt-row-'+i,'Количество, '+esc(e.snapshot.unit),e.quantity,'inputmode="decimal" autocomplete="off"')}</section>`).join(''):'<p class="notice">Состав пока пуст. Добавьте продукт или сохранённый шаблон ниже.</p>';
}
function rtPicker(){
 const r=rtState(),available=state.products;
 return `<details class="progress-details"><summary>Добавить продукт или шаблон</summary><div class="stack"><label class="field">Продукт<select id="rt-product">${available.map(p=>`<option value="${p.id}" ${String(p.id)===r.pickProduct?'selected':''}>${esc(p.name)} · ${p.base} ${esc(p.unit)}</option>`).join('')}</select></label>${field('rt-product-quantity','Количество в единице продукта',r.pickQuantity,'inputmode="decimal"')}<button class="button secondary" data-action="rt-add-product" ${available.length?'':'disabled'}>Добавить продукт в состав</button>${r.templates.length?`<label class="field">Сохранённый шаблон<select id="rt-pick-template"><option value="">Выберите шаблон</option>${r.templates.map(t=>`<option value="${t.id}" ${String(t.id)===r.pickTemplate?'selected':''}>${esc(t.name)}</option>`).join('')}</select></label><button class="button secondary" data-action="rt-add-template">Добавить состав шаблона</button>`:''}</div></details>`;
}
function rtEditorView(){
 const r=rtState(),d=r.draft,plan=d.type==='plan';
 return `<div class="screen routine-screen">${header('rt-editor-cancel',plan?'План приёма':'Шаблон приёма',plan?'План и факт хранятся отдельно. Меню не добавляет съеденную еду автоматически.':'Сохраните сочетание продуктов и повторяйте его в удобный день.')} ${field('rt-name','Название',d.name,'maxlength="80"')}${plan?`${field('rt-plan-date','Дата плана',d.date,`type="date" min="${MIN_DATE}" max="${rtMaxDate()}"`)}<label class="field">Приём пищи<select id="rt-plan-meal">${rtActiveMeal(d.meal)?'':`<option value="${d.meal}" selected>${esc(meals[d.meal]||'Приём')} · архив</option>`}${mealOptions(d.meal)}</select></label>`:''}<div class="routine-list stack">${rtDraftRows()}</div>${rtPicker()}<div id="rt-editor-preview">${rtPreview(d.entries)}</div><p class="error" id="rt-editor-error" role="alert">${esc(r.error||rtDraftError())}</p><button class="button" id="rt-editor-save" data-action="rt-editor-save" ${rtDraftError()?'disabled':''}>${plan?'Сохранить план':'Сохранить шаблон'}</button><button class="button plain" data-action="rt-editor-cancel">Отмена</button><p class="small muted">Изменения справочника и этого ${plan?'плана':'шаблона'} не меняют ранее добавленную еду. Все значения макета демонстрационные.</p></div>`;
}
function rtTemplateView(){
 const r=rtState(),selected=r.templates.find(t=>t.id===r.selectedTemplate),result=selected?rtEntriesResult(selected.entries):null;
 return `<div class="screen routine-screen">${header('home','Шаблоны приёмов','Повторяйте любимые сочетания. В дневник они попадают только после подтверждения.')}<section class="card stack"><h3>Сохранить существующий приём</h3>${field('rt-source-date','Дата дневника',r.sourceDate,`type="date" min="${MIN_DATE}" max="${TODAY}"`)}<label class="field">Приём-источник<select id="rt-source-meal">${state.mealOrder.map(id=>`<option value="${id}" ${id===Number(r.sourceMeal)?'selected':''}>${esc(meals[id])}${state.archivedMeals.includes(id)?' · архив':''}</option>`).join('')}</select></label><p class="error" id="rt-source-error" role="alert">${esc(r.error)}</p><button class="button secondary" data-action="rt-template-capture">Создать из приёма</button><button class="link" data-action="rt-template-new">Составить новый шаблон</button></section><div class="routine-list stack">${r.templates.length?r.templates.map(t=>`<section class="card stack routine-card"><div class="row"><h3>${esc(t.name)}</h3><span class="pill">${t.entries.length} поз.</span></div>${nutrition(rtEntriesResult(t.entries)?.total||null)}<div class="row wrap"><button class="button secondary" data-action="rt-template-select" data-id="${t.id}">Выбрать для дневника</button><button class="link" data-action="rt-template-edit" data-id="${t.id}">Изменить</button><button class="link" data-action="rt-template-delete" data-id="${t.id}">Удалить</button></div></section>`).join(''):'<p class="notice">Шаблонов пока нет. Сохраните свой завтрак из дневника или составьте сочетание продуктов.</p>'}</div>${selected&&result?`<section class="card stack"><span class="eyebrow">Добавление шаблона</span><h2>${esc(selected.name)}</h2>${rtPreview(selected.entries)}${field('rt-apply-date','Дата фактической еды',r.applyDate,`type="date" min="${MIN_DATE}" max="${TODAY}"`)}<label class="field">Приём назначения<select id="rt-apply-meal">${mealOptions(r.applyMeal)}</select></label><p class="small muted">Существующие записи сохранятся. Отметка заполнения дня останется прежней. Повтор добавит ещё один набор.</p><p class="error" id="rt-apply-error" role="alert"></p><button class="button" data-action="rt-template-apply">Проверить и добавить</button></section>`:''}<button class="button secondary" data-action="meal-plan">Открыть план питания →</button></div>`;
}
function rtPlanDates(){
 const r=rtState();if(!rtDateValid(r.planDate,true))return [];
 const start=calendarDay(r.planDate),max=calendarDay(rtMaxDate());
 return Array.from({length:r.planMode==='week'?Math.min(7,max-start+1):1},(_,i)=>dateFromDay(start+i));
}
function rtPlanCard(plan){
 const r=rtState(),result=rtEntriesResult(plan.entries),meal=meals[plan.meal]||'Приём';
 return `<section class="card stack routine-card"><div class="row"><h3>${esc(plan.name)}</h3><span class="pill">${esc(meal)}</span></div><p class="small muted">${dateLabel(plan.date)}${state.archivedMeals.includes(plan.meal)?' · Приём в архиве':''}</p>${nutrition(result?.total||null)}<p class="small">${plan.entries.map(e=>esc(e.name)).join(' · ')}</p>${plan.transfers?.length?`<p class="notice routine-status">Ранее перенесён в факт ${plan.transfers.length} раз. Последний перенос: ${dateLabel(plan.transfers.at(-1).date)} · ${esc(meals[plan.transfers.at(-1).meal]||'Приём')}</p>`:'<p class="small muted">Пока только в плане</p>'}<div class="row wrap"><button class="button secondary" data-action="rt-plan-transfer" data-id="${plan.id}">${plan.transfers?.length?'Добавить в факт ещё раз':'Перенести в факт'}</button><button class="link" data-action="rt-plan-edit" data-id="${plan.id}">Изменить</button><button class="link" data-action="rt-plan-delete" data-id="${plan.id}">Удалить</button></div>${r.transferId===plan.id?`<section class="stack"><h3>Проверка переноса</h3>${rtPreview(plan.entries)}${field('rt-transfer-date','Дата фактической еды',r.transferDate,`type="date" min="${MIN_DATE}" max="${TODAY}"`)}<label class="field">Приём фактической еды<select id="rt-transfer-meal">${mealOptions(r.transferMeal)}</select></label><p class="error" id="rt-transfer-error" role="alert"></p><p class="small muted">Будет добавлено ${plan.entries.length} записей. План сохранится; существующая еда и отметка заполнения дня останутся прежними.</p><button class="button" data-action="rt-plan-confirm" data-id="${plan.id}">Подтвердить перенос</button><button class="button plain" data-action="rt-transfer-cancel">Отмена переноса</button></section>`:''}</section>`;
}
function rtPlanView(){
 const r=rtState(),dates=rtPlanDates();
 return `<div class="screen routine-screen">${header('home','План питания','Сначала идея, потом — отдельная запись факта. План не влияет на графики и уровни.')}<div class="progress-tabs" aria-label="Вид плана" role="group">${[['day','День'],['week','7 дней']].map(([id,label])=>`<button class="button ${r.planMode===id?'':'secondary'}" data-action="rt-plan-mode" data-value="${id}" aria-pressed="${r.planMode===id}">${label}</button>`).join('')}</div>${field('rt-plan-view-date','Начало просмотра',r.planDate,`type="date" min="${MIN_DATE}" max="${rtMaxDate()}"`)}<p class="error" id="rt-plan-view-error" role="alert">${dates.length?'':'Выберите дату не дальше 90 дней от условного сегодня.'}</p><button class="button" data-action="rt-plan-new">Запланировать приём</button><div class="plan-week stack">${dates.map(date=>{const list=r.plans.filter(p=>p.date===date);return `<section class="plan-day stack"><h2>${dateLabel(date)}</h2>${list.length?list.map(rtPlanCard).join(''):'<p class="small muted">Пока без планов</p>'}</section>`;}).join('')}</div><button class="button secondary" data-action="templates">Открыть шаблоны →</button><p class="footer-note">В макете доступно планирование до ${dateLabel(rtMaxDate())}. Ничего не отмечается съеденным автоматически.</p></div>`;
}
function routinesView(){
 if(state.screen==='templates')return rtTemplateView();
 if(state.screen==='meal-plan')return rtPlanView();
 if(['template-edit','plan-edit'].includes(state.screen)&&rtState().draft)return rtEditorView();
 return '';
}
function rtRefreshEditor(){
 const preview=document.getElementById('rt-editor-preview'),error=document.getElementById('rt-editor-error'),save=document.getElementById('rt-editor-save');
 if(preview)preview.innerHTML=rtPreview(rtState().draft.entries);if(error)error.textContent=rtState().error||rtDraftError();if(save)save.disabled=!!rtDraftError();
}
function routinesInput(id,value){
 if(!id.startsWith('rt-'))return false;const r=rtState();r.error='';
 const keys={'rt-source-date':'sourceDate','rt-source-meal':'sourceMeal','rt-apply-date':'applyDate','rt-apply-meal':'applyMeal','rt-product':'pickProduct','rt-product-quantity':'pickQuantity','rt-pick-template':'pickTemplate','rt-transfer-date':'transferDate','rt-transfer-meal':'transferMeal'};
 if(id in keys){r[keys[id]]=['rt-source-meal','rt-apply-meal','rt-transfer-meal'].includes(id)?Number(value):value;
  if(id==='rt-product'){const p=state.products.find(p=>String(p.id)===value);r.pickQuantity=String(p?.base||100);const e=document.getElementById('rt-product-quantity');if(e)e.value=r.pickQuantity;}return true;
 }
 if(id==='rt-plan-view-date'){if(r.planDate===value)return true;r.planDate=value;r.transferId=null;render();return true;}
 if(r.draft&&['template-edit','plan-edit'].includes(state.screen)){
  if(id==='rt-name')r.draft.name=value;
  else if(id==='rt-plan-date')r.draft.date=value;
  else if(id==='rt-plan-meal')r.draft.meal=Number(value);
  else{const row=/^rt-row-(\d+)$/.exec(id);if(row&&r.draft.entries[row[1]])r.draft.entries[row[1]].quantity=value;else return false;}
  rtRefreshEditor();return true;
 }
 return false;
}
function rtOpenDraft(draft){const r=rtState();r.draft=structuredClone(draft);r.initial=JSON.stringify(r.draft);r.error='';r.pickProduct=String(state.products[0]?.id||'');r.pickQuantity=String(state.products[0]?.base||100);r.pickTemplate='';go(draft.type==='plan'?'plan-edit':'template-edit');}
function rtDestinationError(date,meal){return !rtDateValid(date)?'Для факта выберите сегодняшнюю или прошлую дату.':!rtActiveMeal(meal)?'Выберите активный приём пищи.':'';}
function rtInsert(entries,date,meal){
 const result=rtEntriesResult(entries);if(!result||rtDestinationError(date,meal))return null;
 ensureDay(date);const added=result.rows.map(e=>({...structuredClone(e),id:state.nextId++,meal:Number(meal)}));state.days[date].entries.push(...added);
 const sources=added.map(e=>e.sourceId).filter(id=>state.products.some(p=>p.id===id));state.recents=[...new Set([...sources,...state.recents])].slice(0,20);return added.map(e=>e.id);
}
function routinesAction(action,button){
 if(action==='templates'){const r=rtState();r.error='';if(button?.dataset.meal!==undefined){r.sourceDate=state.date;r.sourceMeal=Number(button.dataset.meal);}go('templates');return true;}
 if(action==='meal-plan'){rtState().error='';go('meal-plan');return true;}
 if(!action.startsWith('rt-'))return false;const r=rtState();r.error='';
 if(action==='rt-template-from-meal'){r.sourceDate=state.date;r.sourceMeal=Number(button.dataset.meal);state.screen='templates';return routinesAction('rt-template-capture',button);}
 if(action==='rt-template-capture'){
  if(!rtDateValid(r.sourceDate)){r.error='Выберите сегодняшнюю или прошлую дату.';render();return true;}
  const entries=state.days[r.sourceDate]?.entries.filter(e=>e.meal===Number(r.sourceMeal))||[];
  if(!entries.length){r.error='В этом приёме нет записей. Выберите другой или составьте новый шаблон.';render();return true;}
  rtOpenDraft({type:'template',id:null,name:(meals[r.sourceMeal]||'Мой приём')+' · мой шаблон',entries:entries.map(rtNormalEntry)});return true;
 }
 if(action==='rt-template-new'){rtOpenDraft({type:'template',id:null,name:'',entries:[]});return true;}
 if(action==='rt-template-edit'){const t=r.templates.find(t=>t.id===Number(button.dataset.id));if(t)rtOpenDraft({...t,type:'template'});return true;}
 if(action==='rt-template-select'){r.selectedTemplate=Number(button.dataset.id);r.applyDate=state.date;r.applyMeal=rtActiveMeal(state.meal)?state.meal:rtFirstMeal();render();return true;}
 if(action==='rt-template-delete'){
  const id=Number(button.dataset.id),t=r.templates.find(t=>t.id===id);if(t)modal('Удалить шаблон?',t.name+'. Ранее добавленная еда и состав планов сохранятся.','Удалить шаблон',()=>demoWrite(()=>{r.templates=r.templates.filter(t=>t.id!==id);if(r.selectedTemplate===id)r.selectedTemplate=null;render();}));return true;
 }
 if(action==='rt-template-apply'){
  const t=r.templates.find(t=>t.id===r.selectedTemplate),error=rtDestinationError(r.applyDate,r.applyMeal);
  if(error){document.getElementById('rt-apply-error').textContent=error;return true;}
  if(!t||!rtEntriesResult(t.entries))return true;const data=structuredClone({entries:t.entries,date:r.applyDate,meal:r.applyMeal,name:t.name});
  modal('Добавить шаблон в дневник?',data.name+' → '+dateLabel(data.date)+' · '+meals[data.meal]+'. Будет добавлено '+data.entries.length+' записей. Повтор добавит ещё один набор, существующие записи сохранятся.','Добавить набор',()=>demoWrite(()=>{if(!rtInsert(data.entries,data.date,data.meal))return;state.date=data.date;go('home');state.message='Шаблон добавлен';render();}));return true;
 }
 if(action==='rt-add-product'){
  const p=state.products.find(p=>String(p.id)===r.pickProduct),q=num(r.pickQuantity);
  if(!p||!portionValues(productSnapshot(p),q)){r.error='Выберите продукт и положительное количество в его единице.';rtRefreshEditor();return true;}
  r.draft.entries.push({name:p.name,kind:'portion',sourceId:p.id,snapshot:productSnapshot(p),quantity:String(q)});render();return true;
 }
 if(action==='rt-add-template'){
  const t=r.templates.find(t=>String(t.id)===r.pickTemplate);if(!t){r.error='Выберите сохранённый шаблон.';rtRefreshEditor();return true;}r.draft.entries.push(...structuredClone(t.entries));render();return true;
 }
 if(action==='rt-row-remove'){r.draft.entries.splice(Number(button.dataset.index),1);render();return true;}
 if(action==='rt-editor-cancel'){
  const destination=r.draft?.type==='plan'?'meal-plan':'templates';if(JSON.stringify(r.draft)!==r.initial)modal('Выйти без сохранения?','Сохранённый шаблон, план и дневник останутся прежними.','Выйти без сохранения',()=>go(destination),'Продолжить');else go(destination);return true;
 }
 if(action==='rt-editor-save'){
  if(rtDraftError()){rtRefreshEditor();return true;}const draft=structuredClone(r.draft),result=rtEntriesResult(draft.entries);
  demoWrite(()=>{const list=draft.type==='plan'?r.plans:r.templates,old=list.find(t=>t.id===draft.id),item={id:draft.id??state.nextId++,name:draft.name.trim(),entries:result.rows.map(rtNormalEntry)};
   if(draft.type==='plan')Object.assign(item,{date:draft.date,meal:Number(draft.meal),transfers:structuredClone(old?.transfers||[])});if(old)list[list.indexOf(old)]=item;else list.push(item);
   if(draft.type==='plan'){r.planDate=item.date;r.transferId=null;}r.draft=null;go(draft.type==='plan'?'meal-plan':'templates');});return true;
 }
 if(action==='rt-plan-mode'){r.planMode=button.dataset.value==='week'?'week':'day';r.transferId=null;render();return true;}
 if(action==='rt-plan-new'){rtOpenDraft({type:'plan',id:null,name:'Мой приём',date:rtDateValid(r.planDate,true)?r.planDate:state.date,meal:rtFirstMeal(),entries:[]});return true;}
 if(action==='rt-plan-edit'){const p=r.plans.find(p=>p.id===Number(button.dataset.id));if(p)rtOpenDraft({...p,type:'plan'});return true;}
 if(action==='rt-plan-delete'){
  const id=Number(button.dataset.id),p=r.plans.find(p=>p.id===id);if(p)modal('Удалить план?',p.name+'. Ранее перенесённые записи фактической еды останутся.','Удалить план',()=>demoWrite(()=>{r.plans=r.plans.filter(p=>p.id!==id);r.transferId=null;render();}));return true;
 }
 if(action==='rt-plan-transfer'){
  const p=r.plans.find(p=>p.id===Number(button.dataset.id));if(!p)return true;r.transferId=p.id;r.transferDate=p.date>TODAY?state.date:p.date;r.transferMeal=rtActiveMeal(p.meal)?p.meal:rtFirstMeal();render();return true;
 }
 if(action==='rt-transfer-cancel'){r.transferId=null;render();return true;}
 if(action==='rt-plan-confirm'){
  const p=r.plans.find(p=>p.id===Number(button.dataset.id)),error=rtDestinationError(r.transferDate,r.transferMeal);if(error){document.getElementById('rt-transfer-error').textContent=error;return true;}
  if(!p||!rtEntriesResult(p.entries))return true;const data=structuredClone({id:p.id,entries:p.entries,date:r.transferDate,meal:r.transferMeal,name:p.name});
  modal(p.transfers?.length?'Добавить факт повторно?':'Перенести план в факт?',data.name+' → '+dateLabel(data.date)+' · '+meals[data.meal]+'. '+(p.transfers?.length?'Этот план уже переносили. Повтор создаст новые записи. ':'')+'Добавится '+data.entries.length+' записей. План и существующая еда сохранятся.','Добавить факт',()=>demoWrite(()=>{const plan=r.plans.find(p=>p.id===data.id);if(!plan)return;const ids=rtInsert(data.entries,data.date,data.meal);if(!ids)return;plan.transfers.push({date:data.date,meal:Number(data.meal),recordIds:ids});r.transferId=null;state.date=data.date;go('home');state.message='План перенесён в факт';render();}));return true;
 }
 return false;
}
