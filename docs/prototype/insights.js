/* Scenario-only calendar and reports. Uses the temporary state of index.html. */
function ixState(){
 if(!state.insights)state.insights={month:state.date.slice(0,7),period:7,end:state.date};
 return state.insights;
}
function ixDateValid(date){return calendarDay(date)!==null&&date<=TODAY;}
function ixDates(end,count){
 const last=calendarDay(end),first=calendarDay(MIN_DATE);
 if(last===null)return [];
 return Array.from({length:Math.min(count,last-first+1)},(_,i)=>dateFromDay(last-Math.min(count,last-first+1)+1+i));
}
function ixWeight(d){return Number.isSafeInteger(d?.weightCents)&&d.weightCents>0?d.weightCents:null;}
function ixSleep(d){return Number.isSafeInteger(d?.sleepMinutes)&&d.sleepMinutes>=0?d.sleepMinutes:null;}
function ixFoodStatus(d){return d?.complete===true?'complete':d?.entries?.length?'partial':'missing';}
function ixHasRecord(d){return ixFoodStatus(d)!=='missing'||ixWeight(d)!==null||ixSleep(d)!==null||ixHasMoment(d);}
function ixHasMoment(d){return Boolean(d?.mood||d?.note?.trim()||d?.photos?.length);}
function ixMomentsReport(rows){
 const moments=rows.filter(r=>ixHasMoment(r.record)),photos=moments.reduce((n,r)=>n+(r.record.photos?.length||0),0),notes=moments.filter(r=>r.record.note?.trim()).length;
 return `<section class="card stack"><span class="eyebrow">За кадром цифр</span><h3>Маленькие моменты</h3><div class="report-stats"><div><strong class="report-value">${photos}</strong><p class="small muted">фотографий</p></div><div><strong class="report-value">${notes}</strong><p class="small muted">дней с заметками</p></div></div><p class="small muted">Настроение отмечено за ${moments.filter(r=>r.record.mood).length} дн. Личные моменты не меняют полноту питания и уровень дневника.</p>${moments.length?moments.slice(-3).reverse().map(r=>`<button class="choice" data-action="insight-open-day" data-date="${r.date}"><span class="grow"><strong>${dateLabel(r.date)}</strong><span class="small muted" style="display:block">${esc((r.record.note||'').slice(0,100)||'Открыть моменты дня')}</span></span><span aria-hidden="true">›</span></button>`).join(''):'<p class="small muted">Добавьте фото, настроение или пару слов в «Мой день».</p>'}</section>`;
}
function ixAggregate(dates){
 const rows=dates.map(date=>({date,record:state.days[date]}));
 const complete=rows.filter(r=>ixFoodStatus(r.record)==='complete');
 const partial=rows.filter(r=>ixFoodStatus(r.record)==='partial');
 const weights=rows.map(r=>ixWeight(r.record)).filter(v=>v!==null),sleep=rows.map(r=>ixSleep(r.record)).filter(v=>v!==null);
 const food=Array.from({length:4},(_,i)=>decimalMean(complete.map(r=>decimalSum((r.record.entries||[]).map(e=>decimalFromNumber(e.values[i]))))));
 const top=new Map();
 rows.forEach(r=>(r.record?.entries||[]).forEach(e=>{
  const key=String(e.name),item=top.get(key)||{name:key,count:0,dates:new Set()};item.count++;item.dates.add(r.date);top.set(key,item);
 }));
 return {rows,complete:complete.length,partial:partial.length,missing:dates.length-complete.length-partial.length,active:rows.filter(r=>ixHasRecord(r.record)).length,weight:{mean:integerMean(weights),count:weights.length},sleep:{mean:integerMean(sleep),count:sleep.length},food,top:[...top.values()].sort((a,b)=>b.count-a.count||a.name.localeCompare(b.name,'ru')).slice(0,5)};
}
function ixMonthShift(month,amount){
 const [year,number]=month.split('-').map(Number),absolute=(year-1)*12+number-1+amount;
 if(absolute<0||absolute>119987)return null;
 return String(Math.floor(absolute/12)+1).padStart(4,'0')+'-'+String(absolute%12+1).padStart(2,'0');
}
function ixMonthDates(month){
 const first=calendarDay(month+'-01');if(first===null)return [];
 const next=ixMonthShift(month,1),last=next?calendarDay(next+'-01')-1:calendarDay('9999-12-31');
 return Array.from({length:last-first+1},(_,i)=>dateFromDay(first+i));
}
function ixDayLabel(date){
 const d=state.days[date],food=ixFoodStatus(d),parts=[dateLabel(date),food==='complete'?'питание: день заполнен':food==='partial'?'питание: частично':'питание: нет записей'];
 if(ixWeight(d)!==null)parts.push('есть вес');if(ixSleep(d)!==null)parts.push('есть сон');if(ixHasMoment(d))parts.push('есть личные моменты');
 return parts.join(' · ');
}
function calendarView(){
 const ui=ixState(),month=ui.month,dates=ixMonthDates(month),prev=ixMonthShift(month,-1),next=ixMonthShift(month,1);
 const weekday=(new Date(month+'-01T00:00:00Z').getUTCDay()+6)%7;
 const label=new Intl.DateTimeFormat('ru-RU',{month:'long',year:'numeric',timeZone:'UTC'}).format(new Date(month+'-01T00:00:00Z'));
 const validDates=dates.filter(d=>d<=TODAY),data=ixAggregate(validDates);
 return `<div class="screen calendar-screen">${header('home','Календарь','У каждого дня своя история. Выберите дату, чтобы открыть дневник.')}<section class="card stack"><div class="row"><button class="icon-button" data-action="calendar-prev" aria-label="Предыдущий месяц" ${prev?'':'disabled'}>‹</button><h2 class="calendar-month">${label}</h2><button class="icon-button" data-action="calendar-next" aria-label="Следующий месяц" ${next&&next<=TODAY.slice(0,7)?'':'disabled'}>›</button></div><div class="calendar-grid" aria-label="Дни месяца">${['Пн','Вт','Ср','Чт','Пт','Сб','Вс'].map(d=>`<span class="calendar-weekday" aria-hidden="true">${d}</span>`).join('')}${'<span aria-hidden="true"></span>'.repeat(weekday)}${dates.map(date=>{
  const d=state.days[date],status=ixFoodStatus(d),future=date>TODAY;
  return `<button class="calendar-day ${date===state.date?'is-selected':''} ${date===TODAY?'is-today':''} is-${status==='missing'?'empty':status}" data-action="insight-open-day" data-date="${date}" aria-label="${esc(ixDayLabel(date))}${future?' · будущая дата':''}" ${date===state.date?'aria-pressed="true"':'aria-pressed="false"'} ${future?'disabled':''}><strong>${Number(date.slice(8))}</strong><span class="calendar-dots" aria-hidden="true">${status!=='missing'?`<i class="food-dot ${status==='complete'?'filled':'partial'}"></i>`:''}${ixWeight(d)!==null?'<i class="weight-dot"></i>':''}${ixSleep(d)!==null?'<i class="sleep-dot"></i>':''}${ixHasMoment(d)?'<i class="journal-dot"></i>':''}</span></button>`;
 }).join('')}</div><div class="calendar-legend"><span>● Питание</span><span>◆ Вес</span><span>☾ Сон</span><span>✦ Моменты</span><span>Кольцо — частичное питание</span></div><button class="link" data-action="calendar-current">К текущему месяцу</button></section><section class="card calendar-summary stack"><span class="eyebrow">Этот месяц · до ${validDates.at(-1)?dateLabel(validDates.at(-1)):'начала периода'}</span><div class="report-stats"><div><strong class="report-value">${data.active}</strong><p class="small muted">дней с записями</p></div><div><strong class="report-value">${data.complete}</strong><p class="small muted">дней с заполненным питанием</p></div></div><p class="small muted">Вес: ${data.weight.count} дн. · Сон: ${data.sleep.count} дн. Отсутствие точки означает отсутствие записи, а не нулевое значение.</p></section><button class="button secondary" data-action="report">Посмотреть отчёт</button></div>`;
}
function ixSigned(value,format){return value===0?'Без изменения':(value>0?'+':'−')+format(Math.abs(value));}
function ixDifference(now,previous,format){
 if(now===null||previous===null)return 'Для сравнения нужны записи в обоих периодах';
 const delta=now-previous;return Number.isFinite(delta)?ixSigned(delta,format)+' к предыдущему периоду':'Разница слишком велика для расчёта';
}
function ixMiniChart(rows,key,label){
 const values=rows.map(r=>key==='food'?(ixFoodStatus(r.record)==='complete'?decimalToNumber(decimalSum((r.record?.entries||[]).map(e=>decimalFromNumber(e.values[0])))):null):key==='weight'?ixWeight(r.record):ixSleep(r.record));
 const usable=values.filter(v=>v!==null&&Number.isFinite(v));
 if(!usable.length)return '<p class="small muted">График появится после записей</p>';
 const min=key==='weight'?Math.min(...usable):0,max=Math.max(...usable),padding=max===min?Math.max(1,max*.01):0,low=Math.max(0,min-padding),high=max+padding;
 const x=i=>10+i/Math.max(1,rows.length-1)*280,y=v=>12+(high-v)/(high-low||1)*64;
 let paths='';for(let i=1;i<values.length;i++)if(values[i-1]!==null&&Number.isFinite(values[i-1])&&values[i]!==null&&Number.isFinite(values[i]))paths+=`<path class="chart-line" d="M${x(i-1)},${y(values[i-1])}L${x(i)},${y(values[i])}"/>`;
 const dots=values.map((v,i)=>v!==null&&Number.isFinite(v)?`<circle class="chart-point" cx="${x(i)}" cy="${y(v)}" r="3"><title>${esc(dateLabel(rows[i].date))} · ${esc(key==='weight'?weightText(v)+' кг':key==='sleep'?sleepText(v):fmt(v)+' ккал')}</title></circle>`:'').join('');
 const scale=key==='weight'?`${weightText(low)}–${weightText(high)} кг`:key==='sleep'?`0–${sleepText(high)}`:`0–${fmt(high)} ккал`;
 return `<div class="report-spark progress-chart"><svg viewBox="0 0 300 90" role="img" aria-label="${esc(label)}. Пропуски разрывают линию. ${esc(scale)}."><path class="chart-grid" d="M10 76H290"/>${paths}${dots}</svg><p class="small muted">${esc(scale)} · пропуски не соединены</p></div>`;
}
function ixReportRange(){const ui=ixState();return ixDateValid(ui.end)?ixDates(ui.end,ui.period):null;}
function ixCoverage(label,count,days){return `<div class="report-coverage"><div class="row"><strong>${label}</strong><span>${count} / ${days} дн.</span></div><div class="track"><div class="fill" style="width:${days?count/days*100:0}%"></div></div></div>`;}
function insightsInput(id,value){
 if(id!=='report-end')return false;ixState().end=value;render();return true;
}
function insightsAction(action,button){
 if(action==='calendar'){ixState().month=state.date.slice(0,7);go('calendar');return true;}
 if(action==='report-back'){go('report');return true;}
 if(action==='report'){ixState().end=state.date;go('report');return true;}
 if(action==='calendar-prev'||action==='calendar-next'){
  const ui=ixState(),month=ixMonthShift(ui.month,action==='calendar-prev'?-1:1);if(month&&month<=TODAY.slice(0,7)){ui.month=month;render();}return true;
 }
 if(action==='calendar-current'){ixState().month=TODAY.slice(0,7);render();return true;}
 if(action==='report-period'){const period=Number(button.dataset.period);if([7,30,90].includes(period)){ixState().period=period;render();}return true;}
 if(action==='insight-open-day'){const date=button.dataset.date;if(ixDateValid(date)){state.date=date;ensureDay(date);go('home');}return true;}
 if(action==='report-chart'){
  const metric=button.dataset.metric;if(!['weight','sleep','food'].includes(metric)||!ixReportRange())return true;
  const ui=ixState(),dates=ixReportRange();state.progress={metric,nutrient:0,completeOnly:false,preset:'custom',end:ui.end,from:dates[0],selectedDate:ui.end};go('progress');return true;
 }
 return false;
}
