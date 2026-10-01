const fs = require('node:fs');
const path = require('node:path');

module.exports = async function createActivityMap(browser) {
  const page = await browser.newPage({ viewport: { width: 360, height: 104 }, deviceScaleFactor: 2 });
  try {
    await page.setContent('<body style="margin:0;background:transparent"><canvas width="720" height="208" style="display:block;width:360px;height:104px"></canvas></body>');
    await page.locator('canvas').evaluate(canvas => {
      const context = canvas.getContext('2d');
      const colors = ['#eef0ee', '#dce9de', '#b6d3bd', '#85b697', '#5b9472', '#426f55'];
      const weeks = [
        '0100000', '0012000', '0121100', '1012000',
        '0201310', '0112200', '0023100', '1121000',
        '0233210', '0123410', '0022200', '0132100',
        '1234300', '0243210', '0134510', '1223200',
        '0234300', '1343210', '0234120', '0123100',
        '1234520', '2343410', '0234320', '1345200',
        '2345310', '1234200', '0245310', '1234500',
      ];
      context.scale(2, 2);
      for (let column = 0; column < weeks.length; column += 1) {
        for (let row = 0; row < 7; row += 1) {
          context.fillStyle = colors[Number(weeks[column][row])];
          context.beginPath();
          context.roundRect(14 + column * 12, 12 + row * 12, 8, 8, 2);
          context.fill();
        }
      }
    });
    fs.mkdirSync(path.join(__dirname, 'assets'), { recursive: true });
    await page.locator('canvas').screenshot({
      path: path.join(__dirname, 'assets', 'dot-activity.png'),
      omitBackground: true,
    });
  } finally {
    await page.close();
  }
};
